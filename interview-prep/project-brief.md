# Project Brief

## 证据口径
- `事实`：代码、配置、SQL、脚本可以直接证明。
- `推断`：基于代码做出的合理判断。
- `需要本人补充`：仓库代码无法确认，需你补充背景或部署信息。

## 1. 项目解决了什么问题
- `事实`：这是一个面向“校园稀缺资源调度/预约”的多模块 Spring Cloud Alibaba 项目，README 明确写了资源调度中台；代码里的核心域确实围绕 `user`、`lab`、`resource`、`reservation` 展开。证据：`README.md`；`citen-user-service/src/main/java/com/citen/controller/UserController.java`；`citen-order-service/src/main/java/com/citen/controller/LabController.java`；`citen-order-service/src/main/java/com/citen/controller/ResourceController.java`；`citen-order-service/src/main/java/com/citen/controller/ReservationController.java`。
- `事实`：已实现的核心能力不是“问答本身”，而是底层预约执行、抢占、确认、取消、超时回收和派单。证据：`citen-order-service/src/main/java/com/citen/service/impl/ReservationServiceImpl.java`；`citen-order-service/src/main/java/com/citen/service/DispatchService.java`；`citen-order-service/src/main/java/com/citen/listener/ReservationTimeoutListener.java`。
- `推断`：它更像“上层助手/前端的后端执行引擎”，而不是独立业务门户。README 里提到 Function Calling，但仓库里没有 LLM/Agent 接入代码。证据：`README.md`；仓库内未检索到任何 LLM/Function Calling 入口。
- `需要本人补充`：真实调用方是否是 Web 前端、管理后台，还是大模型助手的 Function Calling，需要你补充。

## 2. 用户或调用方如何使用它
- `事实`：外部请求从 Gateway 进入，当前只配置了 `/user/**` 和 `/reservation/**` 两条路由。证据：`citen-gateway/src/main/resources/application.yml` 中 `spring.cloud.gateway.routes`；`citen-gateway/src/main/java/com/citen/gateway/filter/AuthGlobalFilter.java`。
- `事实`：登录前调用 `/user/code` 和 `/user/login`；登录后可以调用 `/user/me`、`/user/sign`、`/user/sign/count`、`/reservation/reserve/{id}`、`/reservation/confirm/{id}`、`/reservation/cancel/{id}`、`/reservation/admin/page`。证据：`citen-user-service/src/main/java/com/citen/controller/UserController.java`；`citen-order-service/src/main/java/com/citen/controller/ReservationController.java`。
- `事实`：`AuthGlobalFilter` 和 `RefreshTokenInterceptor` 都围绕 `authorization` header 工作，token 存在 Redis 的 `login:token:{token}` 里。证据：`citen-gateway/src/main/java/com/citen/gateway/filter/AuthGlobalFilter.java`；`citen-user-service/src/main/java/com/citen/utils/RefreshTokenInterceptor.java`；`citen-common/src/main/java/com/citen/utils/RedisConstants.java`。
- `推断`：`/lab/**` 和 `/resource/**` 在 `order-service` 里存在，但 Gateway 没有暴露这些路由，可能是内部管理接口，也可能是遗漏。证据：`citen-order-service/src/main/java/com/citen/controller/LabController.java`；`citen-order-service/src/main/java/com/citen/controller/ResourceController.java`；`citen-gateway/src/main/resources/application.yml`。
- `需要本人补充`：前端页面、管理端、以及是否有额外的内部调用链路，代码里都没有展示。

## 3. 技术栈及每项技术的实际作用
- `Spring Boot`：三个可独立启动的服务入口。证据：`citen-gateway/src/main/java/com/citen/gateway/CitenGatewayApplication.java`；`citen-user-service/src/main/java/com/citen/UserApplication.java`；`citen-order-service/src/main/java/com/citen/OrderApplication.java`。
- `Spring Cloud Alibaba / Nacos`：服务注册与发现，Gateway 通过 `lb://user-service`、`lb://order-service` 转发。证据：`pom.xml`；`citen-gateway/src/main/resources/application.yml`；各服务 `application.yml`。
- `Spring Cloud Gateway`：统一入口和鉴权前置。证据：`citen-gateway/src/main/java/com/citen/gateway/filter/AuthGlobalFilter.java`。
- `MyBatis-Plus`：CRUD、分页、条件更新、`ServiceImpl` / `BaseMapper`。证据：`citen-order-service/src/main/java/com/citen/service/impl/ReservationServiceImpl.java`；`citen-order-service/src/main/java/com/citen/config/MybatisConfig.java`；`citen-common/src/main/java/com/citen/entity/*.java`。
- `Redis`：登录验证码、登录态、资源额度、预约去重集合、Redis Stream、签到 bitmap、GEO 坐标、分布式 ID。证据：`citen-user-service/src/main/java/com/citen/service/impl/UserServiceImpl.java`；`citen-order-service/src/main/resources/seckill.lua`；`citen-order-service/src/main/java/com/citen/service/impl/ReservationServiceImpl.java`；`citen-order-service/src/main/java/com/citen/service/DispatchService.java`；`citen-order-service/src/main/java/com/citen/utils/RedisIdWorker.java`。
- `Lua`：在 Redis 内原子完成 quota 检查、重复预约判断、扣减和消息入 Stream。证据：`citen-order-service/src/main/resources/seckill.lua`。
- `Redisson`：预约落库阶段的按用户粒度分布式锁。证据：`citen-order-service/src/main/java/com/citen/config/RedissonConfig.java`；`citen-order-service/src/main/java/com/citen/service/impl/ReservationServiceImpl.java`。
- `RabbitMQ`：预约成功后的延迟消息和死信转发，用于超时回收。证据：`citen-order-service/src/main/java/com/citen/config/RabbitMQConfig.java`；`citen-order-service/src/main/java/com/citen/listener/ReservationTimeoutListener.java`。
- `WebSocket`：给某个 `labId` 下的页面推送预约状态变化和派单结果。证据：`citen-order-service/src/main/java/com/citen/websocket/WebSocketServer.java`；`citen-order-service/src/main/java/com/citen/service/impl/ReservationServiceImpl.java`；`citen-order-service/src/main/java/com/citen/service/DispatchService.java`。
- `OpenFeign`：设计上用于跨服务调用用户服务，但当前 `UserClient` 文件有编译错误，实际没有形成可用闭环。证据：`citen-order-service/src/main/java/com/citen/OrderApplication.java`；`citen-user-api/src/main/java/com/citen/api/client/UserClient.java`。
- `Hutool` / `Lombok` / `Slf4j`：对象拷贝、随机码、字符串校验、数据类和日志。证据：`citen-user-service/src/main/java/com/citen/service/impl/UserServiceImpl.java`；`citen-gateway/src/main/java/com/citen/gateway/filter/AuthGlobalFilter.java`；`citen-order-service/src/main/java/com/citen/config/WebExceptionAdvice.java`。
- `README.md` 提到的 `Sentinel`：仓库里未看到相关依赖或配置，属于文档承诺但代码未落地。证据：`README.md`；仓库内未检索到 Sentinel 相关实现。

## 4. 项目启动入口
- `gateway` 启动入口：`citen-gateway/src/main/java/com/citen/gateway/CitenGatewayApplication.java#main`，端口 `8080`。证据：`citen-gateway/src/main/resources/application.yml`。
- `user-service` 启动入口：`citen-user-service/src/main/java/com/citen/UserApplication.java#main`，端口 `8081`。证据：`citen-user-service/src/main/resources/application.yml`。
- `order-service` 启动入口：`citen-order-service/src/main/java/com/citen/OrderApplication.java#main`，端口 `8082`。它额外启用了 `@EnableFeignClients(basePackages = "com.citen.api.client")`、`@EnableAspectJAutoProxy(exposeProxy = true)` 和 `@MapperScan("com.citen.mapper")`。证据：`citen-order-service/src/main/java/com/citen/OrderApplication.java`。
- `需要本人补充`：Nacos、Redis、MySQL、RabbitMQ 是否都由本机或容器提前启动，代码只能看到地址，没看到编排脚本。证据：各模块 `application.yml`。

## 5. 一个核心请求从入口到数据库或外部服务的完整链路
下面以 `POST /reservation/reserve/{id}` 为主链路。
1. 客户端先打到 Gateway，路径命中 `/reservation/**`，路由到 `order-service`。证据：`citen-gateway/src/main/resources/application.yml`。
2. `AuthGlobalFilter.filter()` 读取 `authorization`，去 Redis 查 `login:token:{token}`，并把 `x-user-id`、`x-user-nick-name` 透传到下游。证据：`citen-gateway/src/main/java/com/citen/gateway/filter/AuthGlobalFilter.java`；`citen-common/src/main/java/com/citen/utils/RedisConstants.java`。
3. `order-service` 的 `MvcConfig` 先走 `RefreshTokenInterceptor`，再走 `LoginInterceptor`，把当前用户放进 `UserHolder`。证据：`citen-order-service/src/main/java/com/citen/config/MvcConfig.java`；`citen-order-service/src/main/java/com/citen/utils/RefreshTokenInterceptor.java`；`citen-order-service/src/main/java/com/citen/utils/LoginInterceptor.java`。
4. `ReservationController.reserveResource()` 调 `ReservationServiceImpl.reserveResource()`。证据：`citen-order-service/src/main/java/com/citen/controller/ReservationController.java`。
5. `reserveResource()` 通过 `RedisIdWorker.nextId("reservation")` 生成预约 ID，然后执行 `seckill.lua`。证据：`citen-order-service/src/main/java/com/citen/service/impl/ReservationServiceImpl.java`；`citen-order-service/src/main/java/com/citen/utils/RedisIdWorker.java`；`citen-order-service/src/main/resources/seckill.lua`。
6. Lua 脚本在 Redis 内原子完成三件事：检查 `resource:quota:{id}`、检查 `resource:reservation:{id}` 里是否已有该用户、扣减 quota 并 `XADD stream.reservations`。证据：`citen-order-service/src/main/resources/seckill.lua`。
7. `ReservationServiceImpl.init()` 启动单线程消费者 `ReservationTaskHandler`，从 Redis Stream 的 `g1/c1` 消费消息。证据：`citen-order-service/src/main/java/com/citen/service/impl/ReservationServiceImpl.java`。
8. 消费线程调用 `handleReservation()`，先用 `RedissonClient.getLock("lock:reservation:{userId}")` 防止同一用户并发重复落库，再通过代理对象 `reservationServiceProxy.createReservation(reservation)` 进入事务方法。证据：`ReservationServiceImpl.handleReservation()`、`createReservation()`、`OrderApplication`。
9. `createReservation()` 先查重复订单，再查 `Resource`，然后用 `ResourceAllocationStrategyFactory` 计算 `allocatedQuota`，再 `update()` 扣数据库 `tb_resource_quota`，最后 `save(reservation)` 写入 `tb_reservation`。证据：`ReservationServiceImpl.createReservation()`；`ResourceAllocationStrategyFactory`；`ComputePointStrategy`；`ResourceQuotaServiceImpl`；`ReservationMapper`、`ResourceQuotaMapper`、`Resource`、`Reservation`。
10. 事务提交后，`registerAfterCommitActions()` 发送 RabbitMQ 延迟消息、通过 `WebSocketServer.sendToShop()` 推送状态，并调用 `DispatchService.dispatchOrder()` 做派单。证据：`ReservationServiceImpl.registerAfterCommitActions()`；`RabbitMQConfig`；`WebSocketServer`；`DispatchService`。
11. 10 秒后延迟队列转死信，`ReservationTimeoutListener.listenReservationTimeoutMessage()` 触发 `markTimeoutBreach()`，回滚 quota 和预约集合。证据：`RabbitMQConfig`；`ReservationTimeoutListener`；`ReservationServiceImpl.markTimeoutBreach()`；`rollbackReservationResource()`。

## 6. 各主要模块的职责
- `citen-common`：共享 DTO、实体、常量、枚举和工具类。证据：`citen-common/src/main/java/com/citen/dto/Result.java`；`LoginFormDTO.java`；`UserDTO.java`；`ScrollResult.java`；`citen-common/src/main/java/com/citen/entity/*.java`；`citen-common/src/main/java/com/citen/utils/*.java`。
- `citen-gateway`：统一入口、路由转发、token 鉴权、用户信息透传。证据：`CitenGatewayApplication.java`；`AuthGlobalFilter.java`；`application.yml`。
- `citen-user-api`：给别的服务提供 `OpenFeign` 接口定义。证据：`citen-user-api/src/main/java/com/citen/api/client/UserClient.java`；`citen-user-api/src/main/java/com/citen/user/api/package-info.java`。
- `citen-user-service`：手机号验证码登录、登录态维护、用户详情、签到。证据：`UserController.java`；`UserServiceImpl.java`；`UserInfoServiceImpl.java`；`MvcConfig.java`；`LoginInterceptor.java`；`RefreshTokenInterceptor.java`。
- `citen-order-service`：资源/实训室/预约全流程调度。证据：`LabController.java`；`LabTypeController.java`；`ResourceController.java`；`ReservationController.java`；`ReservationServiceImpl.java`；`ResourceServiceImpl.java`；`LabServiceImpl.java`；`DispatchService.java`；`ReservationStateTransitionService.java`。

## 7. 核心数据结构和数据库表

### 核心实体 / 表
| 代码结构 | 数据表 | 核心作用 |
|---|---|---|
| `citen-common/src/main/java/com/citen/entity/User.java` | `tb_user` | 登录用户基础信息：`id`、`phone`、`password`、`nickName`、`icon`。 |
| `citen-common/src/main/java/com/citen/entity/UserInfo.java` | `tb_user_info` | 用户扩展资料：`city`、`introduce`、`fans`、`followee`、`gender`、`birthday`、`credits`、`level`。 |
| `citen-common/src/main/java/com/citen/entity/LabType.java` | `tb_lab_type` | 资源分类，如类型名、图标、排序。 |
| `citen-common/src/main/java/com/citen/entity/Lab.java` | `tb_lab` | 实训室/算力中心/场地基础信息，含地理坐标 `x/y`。 |
| `citen-common/src/main/java/com/citen/entity/Resource.java` | `tb_resource` | 可预约资源，含 `labId`、`reserveValue`、`confirmValue`、`resourceMode`、`status`。`quota`、`beginTime`、`endTime` 是 `@TableField(exist = false)`。 |
| `citen-common/src/main/java/com/citen/entity/ResourceQuota.java` | `tb_resource_quota` | 某个资源某时段的可预约额度。`resourceId` 是主键字段。 |
| `citen-common/src/main/java/com/citen/entity/Reservation.java` | `tb_reservation` | 预约单，含 `userId`、`resourceId`、`reserveType`、`status`、`confirmTime`、`completeTime`、`cancelTime`；`allocatedQuota` 为运行期字段。 |

### 关键 Redis 结构
- `login:code:{phone}`：验证码缓存。证据：`RedisConstants.LOGIN_CODE_KEY`；`UserServiceImpl.sendCode()`。
- `login:token:{token}`：登录态 hash。证据：`RedisConstants.LOGIN_USER_KEY`；`UserServiceImpl.login()`；`AuthGlobalFilter.filter()`。
- `resource:quota:{resourceId}`：资源剩余额度。证据：`RedisConstants.RESOURCE_QUOTA_KEY`；`seckill.lua`；`ResourceServiceImpl.addResourceQuota()`。
- `resource:reservation:{resourceId}`：某资源已预约用户集合。证据：`RedisConstants.RESOURCE_RESERVATION_KEY`；`seckill.lua`。
- `stream.reservations`：预约异步落库流。证据：`RedisConstants.RESERVATION_STREAM_KEY`；`seckill.lua`；`ReservationServiceImpl`。
- `sign:{userId}:{yyyyMM}`：月签到 bitmap。证据：`RedisConstants.USER_SIGN_KEY`；`UserServiceImpl.sign()`；`UserServiceImpl.signCount()`。
- `lab:geo:{labTypeId}`：按类型组织的场地坐标集合。证据：`RedisConstants.LAB_GEO_KEY`；`LabServiceImpl.loadLabData()`。
- `delivery:rider:geo`：派单骑手/执行者坐标。证据：`RedisConstants.DELIVERY_RIDER_GEO_KEY`；`DispatchService.initRiderGeoData()`。

## 8. 最复杂的三个技术点
- `Redis Lua + Stream` 的原子抢占链路。`seckill.lua` 在 Redis 内一次完成 quota 检查、重复预约判断、扣减和 `XADD`，避免了“先查后改”竞态。对应代码：`citen-order-service/src/main/resources/seckill.lua`；`ReservationServiceImpl.reserveResource()`。
- `Stream 消费 + Redisson 锁 + 事务回写 + RabbitMQ 延迟/DLX` 的一致性链路。`ReservationServiceImpl.init()` 起消费者，`handleReservation()` 先锁用户，再代理调用 `createReservation()`；`createReservation()` 在事务内扣 DB 额度并落库；提交后再发 MQ、WebSocket、派单；超时由 `ReservationTimeoutListener` 触发补偿。对应代码：`ReservationServiceImpl`；`RabbitMQConfig`；`ReservationTimeoutListener`。
- `预约状态机`。`ReservationStateTransitionService` 用静态规则表把 `PENDING_CONFIRM -> CONFIRMED/CANCELED/TIMEOUT_BREACH`、`CONFIRMED -> COMPLETED` 固化下来，并用乐观条件更新防并发脏写。对应代码：`citen-order-service/src/main/java/com/citen/service/ReservationStateTransitionService.java`；`ReservationServiceImpl.confirmReservation()`；`ReservationServiceImpl.cancelReservation()`；`ReservationServiceImpl.markTimeoutBreach()`。
- `需要本人补充`：如果你想把 `Redis bitmap 签到`、`Redis GEO 派单`、`RedisIdWorker 分布式 ID` 也讲成“复杂点”，可以，但它们更适合当辅助亮点，不是这份代码里最核心的主线。

## 9. 错误处理、日志、测试和安全机制
- `错误处理`：`WebExceptionAdvice.handleRuntimeException()` 捕获运行时异常并统一返回 `Result.fail("服务异常")`。证据：`citen-order-service/src/main/java/com/citen/config/WebExceptionAdvice.java`。
- `错误处理`：业务层大量用 `Result.fail(...)` 显式返回失败原因，例如 `UserServiceImpl.login()`、`ReservationServiceImpl.reserveResource()`、`LabTypeServiceImpl.queryAll()`。证据：对应 service 实现。
- `日志`：`ReservationServiceImpl`、`DispatchService`、`WebSocketServer`、`AuthGlobalFilter` 都有 `slf4j` 日志；`application.yml` 里把 `com.citen` 日志级别设成 `debug`。证据：各类 `@Slf4j` / `Logger`；各模块 `application.yml`。
- `测试`：仓库里未检索到 `src/test/java` 或 `*Test.java`；虽然各模块 `pom.xml` 都依赖了 `spring-boot-starter-test`，但当前没有可见测试。证据：各模块 `pom.xml`；仓库搜索结果。
- `安全`：Gateway 和 MVC 拦截器都做了 token 校验与登录态恢复；`LoginInterceptor` 在没有 `UserHolder` 时直接返回 401。证据：`AuthGlobalFilter.java`；`RefreshTokenInterceptor.java`；`LoginInterceptor.java`；`MvcConfig.java`。
- `安全`：当前不是 JWT，而是“token + Redis hash”模式。证据：`AuthGlobalFilter.java`；仓库里未检索到 JWT 相关实现。
- `需要本人补充`：是否还有网关外的鉴权、RBAC、审计、限流、黑名单、密码登录/短信服务，仓库代码无法确认。

## 10. 当前代码的明显缺陷或技术债
- `[P0]` `citen-user-api/src/main/java/com/citen/api/client/UserClient.java` 末尾有残缺的 `Object.`，`mvn -q -DskipTests compile` 已在该文件失败。这个问题会直接导致整个工程无法通过编译。
- `[P1]` `citen-order-service/src/main/java/com/citen/service/impl/ReservationServiceImpl.java#resolveAllocationStrategyType()` 无论 `resourceMode` 取什么值，最后都返回 `ResourceAllocationStrategyType.COMPUTE_POINT`，导致 `ResourceAllocationStrategyFactory` 目前只有一个实际策略。
- `[P1]` `src/main/resources/db/citen_dp.sql` 里的 `tb_resource_quota` 主键写成了 `voucher_id`，与实体 `ResourceQuota.resourceId`、表语义都不一致，明显是旧 SQL 残留或脚本错误。证据：`citen-common/src/main/java/com/citen/entity/ResourceQuota.java`；`src/main/resources/db/citen_dp.sql`。
- `[P2]` `citen-user-service/src/main/java/com/citen/controller/UserController.java#logout()` 直接返回 `Result.fail("功能未完成")`，登出没有真正实现。
- `[P2]` `UserServiceImpl.sendCode()` 只是把验证码写 Redis 并打 debug 日志，没有看到短信/邮件发送通道；`LoginFormDTO.password` 也没有被实际使用。证据：`UserServiceImpl.java`；`LoginFormDTO.java`。
- `[P2]` README 提到 `Sentinel`、`Docker / Docker Compose`、`OpenAPI / Swagger`，但仓库里没有对应依赖、配置或文件。证据：`README.md`；仓库检索结果。
- `[P2]` `application.yml`、`RedissonConfig` 里直接写了 Redis/MySQL/RabbitMQ 的地址和密码，部署可移植性和安全性都一般。证据：`citen-gateway/src/main/resources/application.yml`；`citen-order-service/src/main/resources/application.yml`；`citen-user-service/src/main/resources/application.yml`；`citen-order-service/src/main/java/com/citen/config/RedissonConfig.java`。
- `[P2]` `DispatchService.initRiderGeoData()` 使用了硬编码骑手坐标和姓名，明显是 demo/样例数据，不适合生产。证据：`citen-order-service/src/main/java/com/citen/service/DispatchService.java`。
- `[P3]` Redis Stream 消费组 `g1/c1` 在代码里没有看到创建逻辑，可能依赖外部初始化，也可能是实现缺口。证据：`ReservationServiceImpl.java`；仓库内未检索到 `createGroup` / `XGROUP`。
- `[P3]` `OrderApplication` 开了 `@EnableAspectJAutoProxy(exposeProxy = true)`，但仓库内没检索到 `AopContext.currentProxy()` 的使用，当前价值不明显。证据：`citen-order-service/src/main/java/com/citen/OrderApplication.java`；仓库检索结果。
- `[P3]` `SystemConstants.IMAGE_UPLOAD_DIR` 硬编码 Windows 路径，跨环境部署不友好。证据：`citen-common/src/main/java/com/citen/utils/SystemConstants.java`。

## 11. 面试官最可能深入追问的部分
- 为什么要先用 Lua 在 Redis 原子抢占，而不是直接查 DB 再更新？证据：`seckill.lua`；`ReservationServiceImpl.reserveResource()`。
- Stream 消费为什么要配 `Redisson` 锁和代理调用 `createReservation()`？如何避免重复消费和同一用户并发下单？证据：`ReservationServiceImpl.handleReservation()`；`createReservation()`；`@EnableAspectJAutoProxy(exposeProxy = true)`。
- RabbitMQ 的延迟队列、死信队列、超时回收分别解决什么问题？事务提交前后怎么保证顺序？证据：`RabbitMQConfig.java`；`ReservationServiceImpl.registerAfterCommitActions()`；`ReservationTimeoutListener.java`。
- 预约状态机有哪些合法迁移，非法迁移怎么处理？证据：`ReservationStateTransitionService.java`。
- 为什么 `resolveAllocationStrategyType()` 现在只有一个分支？如果以后要支持不同资源模式，怎么扩展？证据：`ReservationServiceImpl.java`；`ResourceAllocationStrategyFactory.java`；`ResourceAllocationStrategyType.java`。
- Gateway 为什么只路由 `/user/**` 和 `/reservation/**`，`/lab/**`、`/resource/**` 是内部接口还是遗漏？证据：`citen-gateway/src/main/resources/application.yml`；`LabController.java`；`ResourceController.java`。
- 这套 token 方案和 JWT 的取舍是什么？为什么没看到 logout、token revoke、权限分级？证据：`AuthGlobalFilter.java`；`RefreshTokenInterceptor.java`；`UserController.logout()`。

## 12. 建议优先阅读的文件列表
1. `README.md`：先看项目定位、目标场景和 README 里声称的能力，再和代码做对照。
2. `citen-gateway/src/main/java/com/citen/gateway/filter/AuthGlobalFilter.java`：理解统一入口、token 校验和用户透传。
3. `citen-user-service/src/main/java/com/citen/service/impl/UserServiceImpl.java`：看登录、验证码、签到怎么做。
4. `citen-user-service/src/main/java/com/citen/utils/RefreshTokenInterceptor.java` 和 `LoginInterceptor.java`：看登录态如何回填到 `UserHolder`。
5. `citen-order-service/src/main/java/com/citen/service/impl/ReservationServiceImpl.java`：这是整库最核心的业务文件。
6. `citen-order-service/src/main/resources/seckill.lua`：看高并发抢占如何在 Redis 里一次完成。
7. `citen-order-service/src/main/java/com/citen/config/RabbitMQConfig.java` 和 `ReservationTimeoutListener.java`：看超时回收链路。
8. `citen-order-service/src/main/java/com/citen/service/ReservationStateTransitionService.java`：看状态机和条件更新。
9. `citen-order-service/src/main/java/com/citen/service/DispatchService.java` 和 `websocket/WebSocketServer.java`：看 GEO 派单和实时通知。
10. `citen-common/src/main/java/com/citen/entity/Reservation.java`、`Resource.java`、`Lab.java`、`LabType.java`、`User.java`、`UserInfo.java`：把领域模型和表结构对齐。
11. `src/main/resources/db/citen_dp.sql`：核对真实表结构，重点看 `tb_resource_quota`、`tb_reservation`、`tb_lab`、`tb_user`。
12. `citen-user-api/src/main/java/com/citen/api/client/UserClient.java`：先看这里的编译错误，再决定是否要在面试里主动解释当前缺陷。

