# 校园信息实验室资源调度与预约系统面试口径

## 构建环境口径

- 项目源码层声明的 `java.version` 是 `1.8`，这是当前编译目标版本，不等于推荐的 Maven 启动 JDK。
- 当前仓库推荐使用 **JDK17 运行 Maven 构建**，这样更稳妥，也和 Spring Boot 2.6.13 的常见使用区间更匹配。
- 当前本机 `mvn -version` 实际跑在 **JDK21** 上，而 `java -version` 指向的是 JRE8，构建环境本身是混用状态。
- 在这个混用环境下，普通 `mvn package` 目前仍会在 `spring-boot-maven-plugin:2.6.13:repackage` 阶段失败。
- 如果现场环境暂时只能用当前 JDK21，可以用 `mvn -q -DskipTests "-Dspring-boot.repackage.skip=true" package` 先验证源码编译通过，但这只是构建兜底口径，不是最终推荐构建方式。

## 当前能真实保留的表述

- 当前项目是 `gateway-service`、`user-service`、`order-service` 三服务的 Maven 多模块工程。
- Gateway 真实存在，但统一鉴权基于 Redis Token 登录态，不是 JWT。
- 预约主链路的异步落库真实使用的是 Redis Stream，不是 RabbitMQ。
- Lua 脚本真实完成了 Redis quota 判断、重复预约判断、Redis 预扣减和 `XADD stream.reservations`。
- Redisson 分布式锁真实存在，使用位置是在 Redis Stream 消费后的落库阶段。
- RabbitMQ 真实职责是 `TTL + DLX` 超时补偿，不是预约异步落库主链路。
- WebSocket 真实存在，当前主要按 `labId` 做预约状态广播。

## 本轮轻量状态流转服务补强后可加强的表述

- 预约状态已经在代码层统一收口为共享枚举 `ReservationStatus`。
- 预约状态事件已经抽成轻量事件枚举 `ReservationStatusEvent`，包括 `CONFIRM / CANCEL / TIMEOUT / COMPLETE`。
- 当前新增了轻量状态流转服务 `ReservationStateTransitionService`，集中管理合法流转规则，而不是把规则散在确认、取消、超时三个方法里。
- 当前已经补齐并能真实讲的基础状态流转是：
  - `PENDING_CONFIRM -> CONFIRMED`
  - `PENDING_CONFIRM -> CANCELED`
  - `PENDING_CONFIRM -> TIMEOUT_BREACH`
- 状态更新统一依赖 DB 条件更新保证并发安全。
- 主动取消和超时补偿都会同步回滚：
  - DB `tb_resource_quota.quota = quota + 1`
  - Redis `resource:quota:{resourceId}` 自增回滚
  - Redis `resource:reservation:{resourceId}` 执行 `SREM userId`
- Redis Stream 消费侧仍然是“处理成功才 ACK”。
- Redisson `tryLock()` 失败时不会再静默 ACK，消息会保留在 pending list 后续重试。

## 当前真实状态流转口径

- 当前真实主链路能稳讲的是：
  - `PENDING_CONFIRM -> CONFIRMED`
  - `PENDING_CONFIRM -> CANCELED`
  - `PENDING_CONFIRM -> TIMEOUT_BREACH`
- `COMPLETED` 目前仍然只是语义和事件预留，当前仓库没有完整完成态接口和完整生命周期闭环。
- 因此当前可以说“做了轻量状态流转服务和条件更新控制”，不能说“完整状态机已实现”。

## 当前仍然不能写进简历的点

- 不能写 JWT。
- 不能写 Feign 拦截器跨服务上下文透传。
- 不能写自定义注解策略工厂。
- 不能写完整状态机。
- 不能写 RabbitMQ 异步落库。
- 不能写 `QPS 1200+`、`P99 50ms`、`500 并发线程` 这类量化压测数据。

## 必须主动说明的限制

- 当前仍不是完整分布式事务。
- 当前没有 producer confirm、没有 outbox、没有事务消息表。
- 当前超时补偿和主动取消虽然补齐了 Redis quota 和用户预约集合，但 Redis 与 DB 之间依然属于最终一致性设计，不是强事务。
- 当前代码做的是“状态常量化 + 轻量事件枚举 + 轻量状态流转服务 + 条件更新”，不是 Spring StateMachine，也不是完整状态机框架。
- 当前仓库仍有 legacy 数据和命名残留，面试时不要夸成“完全从零设计的全新项目”。
