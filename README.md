# 实验室资源预约与库存同步平台

这是一个面向实验室工位、设备和计算节点的 Java 后端练习项目。用户可以按日期和时间段预约资源，在规定时间内确认；未确认的预约会自动过期并释放占用。

项目重点是把一次预约从 Redis 原子校验、Redis Stream 异步落库、RabbitMQ 超时取消到 WebSocket 状态通知串成完整链路。它是学生项目，不宣称生产级高可用、分布式事务或未经验证的性能指标。

## 模块

```text
citen-common         公共实体、DTO、状态枚举、Redis 常量
citen-gateway        统一路由、Token 校验、用户身份透传、WebSocket 路由
citen-user-service   验证码登录、Token 登录态、用户签到
citen-order-service  实验室、资源、预约、超时取消、状态通知
```

服务默认端口：Gateway `8080`、用户服务 `8081`、预约服务 `8082`、Nacos `8848`。

## 技术栈

- Spring Boot 2.6、Spring Cloud Gateway、Nacos
- MyBatis-Plus、MySQL
- Redis、Lua、Redis Stream、Redisson
- RabbitMQ TTL + DLX
- WebSocket

仓库中没有 Sentinel、OpenFeign、Kafka、Elasticsearch、Seata，也没有 JWT。

## 预约模型

预约包含：`reservationId`、`userId`、`resourceId`、预约日期、开始时间、结束时间、确认过期时间和状态。

状态流转集中在 `ReservationStateTransitionService`：

```text
PENDING -> CONFIRMED -> COMPLETED
PENDING -> CANCELLED
PENDING -> EXPIRED
```

所有状态更新都带旧状态条件。确认还要求 `expire_at > now`，超时要求 `expire_at <= now`，用于解决用户确认和超时消费同时发生时的竞态。

## 一次预约怎么走

1. Gateway 校验 Redis Token，把用户 ID 通过请求头传给预约服务。
2. 预约服务校验资源、日期、时间段、开放时间和容量配置。
3. Lua 在 Redis 内原子检查同一用户重复时间段、资源每分钟占用计数和容量上限。
4. Lua 写入占用、预约元数据和 Redis Stream，HTTP 快速返回预约 ID。
5. Stream 消费者按资源加 Redisson 锁，在 MySQL 事务中锁资源行并再次检查时间段容量后落库。
6. 数据库提交后发送 RabbitMQ 延迟消息，并推送 `PENDING` 状态。
7. 用户按时确认则 `PENDING -> CONFIRMED`；到期未确认则 MQ 消费或定时恢复任务执行 `PENDING -> EXPIRED`。
8. 取消、过期、完成会创建数据库补偿任务；事务提交后执行幂等 Lua，释放 Redis 时间段占用。

## Redis Lua 边界

`seckill.lua` 只保证 Redis 内部操作的原子性：

- 资源容量从 `resource:quota:{resourceId}` 读取。
- 资源每分钟占用计数存入 `reservation:slots:{resourceId}:{date}`。
- 同一用户的分钟位图存入 `reservation:user:slots:{userId}:{resourceId}:{date}`。
- 预约元数据存入 `reservation:meta:{reservationId}`。
- 成功后写入 `stream.reservations`。
- 如果 `XADD` 失败，脚本在返回前撤销本次 Redis 占用。

Lua 不保证 Redis 与 MySQL 的分布式事务。MySQL 落库连续失败三次后，消费者才执行 `release-reservation.lua` 回滚本次 Redis 占用。释放脚本通过预约元数据状态保证重复执行不会重复回补。

## Redis Stream 处理

- 服务启动时用 `XGROUP CREATE ... MKSTREAM` 创建消费组。
- 启动后先处理当前消费者的 Pending List，再读取新消息。
- 每个服务实例使用随机 Consumer Name；恢复任务使用同一实例派生出的独立 Consumer Name，通过 `XAUTOCLAIM` 接管其他宕机消费者遗留的 stale Pending，避免与正常消费线程并行处理同一条消息。
- 默认只接管 idle 超过 60 秒的消息，每 20 秒最多认领 10 条，三项参数都可通过 `reservation.stream.*` 配置。
- 认领后的消息继续进入原有统一处理方法，不改变成功 ACK、失败重试和最终补偿规则。
- Redisson 锁获取失败、数据库失败、ACK 失败时不提前 ACK。
- 数据库主键 `reservationId` 使重复 Stream 消息幂等。
- 业务落库连续失败三次后，先完成 Redis 补偿，再写 `stream.reservations.failed`，最后 ACK。
- 畸形消息重试三次后转失败 Stream，不执行资源补偿。

跨消费者 Pending 恢复依赖 Redis 6.2 引入的 `XAUTOCLAIM`，因此预约服务要求 Redis >= 6.2。

## RabbitMQ 超时处理

- 数据库提交后发送仅包含预约 ID 的消息。
- 每条消息设置 TTL，过期后由 DLX 路由到超时队列。
- Publisher Confirm 和 returned message 都通过后，才把 `timeout_message_sent` 标记为真。
- 发送失败的 PENDING 预约由定时任务重发；已经到期的 PENDING 预约由定时任务兜底过期。
- 消费端按预约 ID 回查数据库，只允许 `PENDING -> EXPIRED`，重复消息不会重复释放资源。
- 监听器失败重试三次后进入消费者失败队列并记录日志。

## WebSocket

Gateway 路由 `/ws/**`，浏览器客户端可通过查询参数传 Token。Gateway 校验后透传用户 ID，握手阶段拒绝缺少用户 ID 的连接。Session 同时按用户和实验室保存，断开时清理。推送在数据库事务提交后异步执行，失败不回滚核心事务。

## 运行依赖

启动完整链路需要：

- JDK 8 或更高版本
- Maven
- MySQL 8
- Redis >= 6.2
- RabbitMQ
- Nacos

连接信息都可以通过 `MYSQL_*`、`REDIS_*`、`RABBITMQ_*`、`NACOS_ADDR` 环境变量覆盖。

常用验证命令：

```bash
mvn clean test
mvn package -DskipTests
```

## 当前测试范围

现有单元和契约测试覆盖：

- 同一用户重复预约识别
- 多用户竞争有限容量
- 非重叠时间段不互相消耗容量
- Stream 重复消息幂等
- stale Pending 的 idle 阈值、跨消费者认领和统一处理流程
- Redisson 锁失败不 ACK
- 数据库连续失败后的 Redis 补偿、失败 Stream 和 ACK 顺序
- 数据库成功后的 Redis/ACK 异常不会错误补偿
- 畸形 Stream 消息退出 Pending List
- 重复 Redis 补偿
- 重复 MQ 超时和确认/超时状态竞态
- 非法状态转换

这些测试没有启动真实 Redis、RabbitMQ 或 Nacos，因此不能替代中间件集成测试和压测。

## 简历边界

可以描述：Redis Lua 原子预约校验、按分钟容量控制、Redis Stream 异步落库与失败补偿、RabbitMQ TTL + DLX 超时取消、数据库 CAS 状态流转、WebSocket 状态通知。

不能描述：生产级分布式事务、强一致性、已验证的高可用、百万数据 SQL 优化、具体 QPS/冲突率/性能提升、日均业务量、轮询下降比例。除非以后补充真实测试和证据。
