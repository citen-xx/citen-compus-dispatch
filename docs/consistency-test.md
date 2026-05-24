# 预约主链路一致性与轻量状态流转说明

## 构建环境说明

- 根 `pom.xml` 当前声明 `java.version=1.8`，项目没有单独配置 `maven-compiler-plugin`，主要依赖 Spring Boot Parent 的默认编译配置。
- 各模块使用的 `spring-boot-maven-plugin` 版本跟随 `spring-boot-starter-parent:2.6.13`。
- 当前本机环境里：
  - `java -version` 指向的是 JRE8
  - `mvn -version` 实际使用的是 JDK21
- 因此普通 `mvn package` 失败不是因为源码必须升级到 Java21，而是因为 **Maven 运行时 JDK21** 与 **Spring Boot 2.6.13 repackage 阶段** 在当前环境下不稳定。
- 当前推荐构建方式：
  - 首选：切换到 **JDK17** 后执行 `mvn -q -DskipTests package`
  - 备选：在当前 JDK21 环境下执行 `mvn -q -DskipTests "-Dspring-boot.repackage.skip=true" package` 验证源码编译

## 当前主链路职责划分

- Redis + Lua
  - 判断 `resource:quota:{resourceId}` 是否大于 0
  - 判断 `resource:reservation:{resourceId}` 是否已经包含当前 `userId`
  - 执行 Redis quota 预扣减
  - 写入 `stream.reservations`
- Redis Stream
  - 真实承担预约异步落库削峰
  - 消费成功后才 ACK
  - 失败消息保留在 pending list，等待后续重试
- Redisson
  - 在 Stream 消费后的落库阶段按 `userId` 加锁
- RabbitMQ
  - 负责 `TTL + DLX` 超时补偿
  - 不负责异步落库

## 当前状态语义与事件

代码层已经统一收口到共享枚举：

- `ReservationStatus`
  - `PENDING_CONFIRM(1, "待确认")`
  - `CONFIRMED(2, "已确认")`
  - `COMPLETED(3, "已完成")`
  - `CANCELED(4, "已取消")`
  - `TIMEOUT_BREACH(5, "超时违约")`
- `ReservationStatusEvent`
  - `CONFIRM`
  - `CANCEL`
  - `TIMEOUT`
  - `COMPLETE`

## 轻量状态流转服务

本轮新增了 `ReservationStateTransitionService`，它不是 Spring StateMachine，只做三件事：

1. 集中维护合法流转规则
2. 判断 `currentStatus + event` 是否允许迁移
3. 用 DB 条件更新执行状态变更

核心方法：

- `canTransit(Integer currentStatus, ReservationStatusEvent event)`
- `targetStatus(Integer currentStatus, ReservationStatusEvent event)`
- `transitionReservationStatus(Long reservationId, Long userId, ReservationStatusEvent event)`

## 合法流转规则

当前集中管理的合法规则是：

- `PENDING_CONFIRM + CONFIRM -> CONFIRMED`
- `PENDING_CONFIRM + CANCEL -> CANCELED`
- `PENDING_CONFIRM + TIMEOUT -> TIMEOUT_BREACH`
- `CONFIRMED + COMPLETE -> COMPLETED`

其中 `COMPLETE` 只是预留规则，当前没有完成态业务接口。

## 当前真实基础状态流转

当前仓库已经补齐并能真实讲的状态流转是：

- `PENDING_CONFIRM -> CONFIRMED`
- `PENDING_CONFIRM -> CANCELED`
- `PENDING_CONFIRM -> TIMEOUT_BREACH`

当前没有完整完成态接口，也没有完整生命周期闭环，因此这里只能叫：

- **状态语义收口**
- **轻量状态流转服务**
- **条件更新控制**

不能叫“完整状态机”。

## confirm / cancel / timeout 的改造方式

### 1. 确认预约

- 接口：`POST /reservation/confirm/{id}`
- 流程：
  - 校验预约存在
  - 校验当前用户就是预约所属用户
  - 调用 `ReservationStateTransitionService.canTransit(..., CONFIRM)` 判断是否合法
  - 调用 `transitionReservationStatus(reservationId, currentUserId, CONFIRM)` 做 DB 条件更新
  - 成功后推送 WebSocket
- 不回滚库存
- 不删除 Redis 用户预约集合

### 2. 主动取消预约

- 接口：`POST /reservation/cancel/{id}`
- 流程：
  - 校验预约存在
  - 校验当前用户就是预约所属用户
  - 调用 `canTransit(..., CANCEL)` 判断是否合法
  - 调用 `transitionReservationStatus(reservationId, currentUserId, CANCEL)` 做 DB 条件更新
  - 成功后复用统一回滚逻辑
  - 成功后推送 WebSocket

### 3. 超时补偿

- 监听器仍然只负责把消息交给 `markTimeoutBreach`
- `markTimeoutBreach` 现在通过 `canTransit(..., TIMEOUT)` 和 `transitionReservationStatus(reservationId, null, TIMEOUT)` 完成状态迁移
- 迁移成功后复用统一回滚逻辑

## 共享回滚逻辑

`cancelReservation()` 和 `markTimeoutBreach()` 现在共同复用统一的私有回滚方法：

- DB quota 回滚
- Redis quota 回滚
- Redis 用户预约集合回滚

日志会区分 `cancel` 和 `timeout` 两种 reason，并记录：

- `reservationId`
- `resourceId`
- `userId`
- DB quota 是否回滚成功
- Redis quota 是否回滚成功
- Redis set 是否移除成功

## 并发安全

当前通过 `where status = PENDING_CONFIRM` 的条件更新保证这些冲突场景安全：

1. 确认和超时并发
   - 只有一个能成功更新状态
   - 确认先成功后，超时不会回滚库存
   - 超时先成功后，确认会失败
2. 取消和超时并发
   - 只有一个能成功更新状态
   - 不会重复回滚库存
3. 重复取消
   - 第一次成功
   - 第二次因为状态已变化而失败，不重复回滚
4. 重复确认
   - 第一次成功
   - 第二次因为状态已变化而失败
5. 取消别人预约
   - 直接因 `userId` 校验失败
6. 已确认预约收到超时消息
   - 因为不满足 `PENDING_CONFIRM + TIMEOUT`，不会回滚库存

## 之前的一致性补强仍然保留

- 超时补偿路径仍然会同时处理：
  - DB quota 回滚
  - Redis quota 回滚
  - Redis 预约集合回滚
- Redis Stream 仍然是异步落库主链路
- Redis Stream 仍然是成功后才 ACK
- Redisson `tryLock()` 失败仍然不会静默 ACK
- `createReservation()` 失败仍然会抛异常并保留消息在 pending list
- RabbitMQ 仍然只负责 `TTL + DLX` 超时补偿

## 本轮未做的内容

- 没有把 RabbitMQ 改成异步落库主链路
- 没有做 JWT 改造
- 没有做 Feign 拦截器
- 没有做自定义注解策略工厂
- 没有做完整状态机
- 没有引入 Spring StateMachine
- 没有伪造压测报告
- 没有补 producer confirm / outbox / 事务消息表
- 没有升级 Spring Boot Maven Plugin 或大改 `pom`

## 构建与验证

### 已执行

1. `mvn -q -DskipTests package`
   - 失败
   - 失败点仍在 `spring-boot-maven-plugin 2.6.13` 的 `repackage` 阶段
2. `mvn -q -DskipTests "-Dspring-boot.repackage.skip=true" package`
   - 成功
   - 说明：源码层面当前改动可以通过编译

### 本地运行时验证限制

仓库当前没有 Docker / Docker Compose，也没有内嵌 Redis / MySQL / RabbitMQ 测试环境，因此本轮没有在本地直接拉起完整链路做端到端回放。

### 已完成的代码级验证

- 合法状态流转规则已经集中到轻量状态流转服务
- 确认接口只允许 `PENDING_CONFIRM -> CONFIRMED`
- 取消接口只允许 `PENDING_CONFIRM -> CANCELED`
- 超时补偿只允许 `PENDING_CONFIRM -> TIMEOUT_BREACH`
- 取消和超时复用了同一套库存与 Redis 状态回滚逻辑
- 状态更新仍然统一依赖 DB 条件更新
- Redis Stream ACK 仍然只在成功路径执行

## 后续建议

如果下一轮继续写代码，优先考虑是否真的需要补：

1. `COMPLETED` 完成态接口
2. 更清晰的预约列表查询与用户侧查询接口
3. Redis Stream consumer group 初始化和更明确的幂等策略

如果下一轮先做面试材料，则现在已经可以基于这版真实代码收敛项目介绍、亮点讲法和并发冲突答法。
