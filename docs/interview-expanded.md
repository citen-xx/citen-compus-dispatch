# 校园资源预约项目面试问答稿

说明：
- 本稿按“项目已经完整交付”的面试口径整理，只基于仓库里的代码、配置、SQL、测试痕迹和 README，不参考 Git 提交记录。
- 所有回答优先按完整闭环来讲，细节追问再用 `需要本人补充` 做补充说明。
- 面试时建议优先讲 `Redis + Lua`、`Redis Stream`、`Redisson`、`RabbitMQ TTL/DLX`、`状态流转` 这条主线。

## 1. 项目一句话怎么介绍？

Q1：这个项目到底是做什么的？

A：这是一个面向校园稀缺资源预约与调度的多模块后端项目，Gateway 负责统一入口和 token 校验，user-service 负责登录态和用户相关能力，order-service 负责资源预约、落库、超时补偿和状态流转。核心链路是 `Redis + Lua` 预扣减、`Redis Stream` 异步落库、`Redisson` 用户级锁、`RabbitMQ TTL/DLX` 超时补偿。证据：`README.md`；`citen-gateway/src/main/java/com/citen/gateway/filter/AuthGlobalFilter.java`；`citen-user-service/src/main/java/com/citen/service/impl/UserServiceImpl.java`；`citen-order-service/src/main/java/com/citen/service/impl/ReservationServiceImpl.java`。

Q2：如果面试官只给你 30 秒，你怎么说？

A：我会说这是一个校园资源预约后端，不是普通 CRUD。用户请求先过 Gateway 做 Redis token 校验，然后预约请求先走 `Redis + Lua` 原子扣减额度和判重，再把消息写进 `Redis Stream` 异步落库；消费侧用 `Redisson` 用户级锁和数据库条件更新保证一致性，超时则用 `RabbitMQ TTL/DLX` 做补偿。证据：`AuthGlobalFilter.java`；`seckill.lua`；`ReservationServiceImpl.java`；`RabbitMQConfig.java`。

Q3：这个项目现在是单体还是微服务？

A：现在是 `Maven` 多模块微服务化工程，主要运行服务是 `gateway`、`user-service`、`order-service`。它不是非常细粒度的服务群，但已经拆成了三个独立运行入口。证据：根 `pom.xml`；`citen-gateway/src/main/java/com/citen/gateway/CitenGatewayApplication.java`；`citen-user-service/src/main/java/com/citen/UserApplication.java`；`citen-order-service/src/main/java/com/citen/OrderApplication.java`。

Q4：项目的主要业务域有哪些？

A：主要围绕 `User`、`Lab`、`LabType`、`Resource`、`ResourceQuota`、`Reservation` 这几个域展开。`User` 负责登录用户，`Lab`/`LabType` 负责资源中心与分类，`Resource` 负责可预约资源，`ResourceQuota` 负责额度，`Reservation` 负责预约单。证据：`citen-common/src/main/java/com/citen/entity/User.java`；`Lab.java`；`LabType.java`；`Resource.java`；`ResourceQuota.java`；`Reservation.java`。

## 2. 入口和鉴权怎么讲？

Q5：请求入口在哪里？

A：所有外部 HTTP 请求先进入 Gateway，当前路由只显式配置了 `/user/**` 和 `/reservation/**` 两条。证据：`citen-gateway/src/main/resources/application.yml`。

Q6：Gateway 做了什么？

A：`AuthGlobalFilter` 先放行白名单 `"/user/code"` 和 `"/user/login"`，其他请求必须带 `authorization`。它会去 Redis 查 `login:token:{token}`，存在就续期，并把 `x-user-id`、`x-user-nick-name` 透传给下游。证据：`citen-gateway/src/main/java/com/citen/gateway/filter/AuthGlobalFilter.java`；`citen-common/src/main/java/com/citen/utils/RedisConstants.java`。

Q7：这里是 JWT 吗？

A：不是。当前实现是 `token + Redis hash` 模式，不存在 JWT 的签发和验签代码。证据：`AuthGlobalFilter.java`；`RefreshTokenInterceptor.java`；仓库内未检索到 JWT 相关实现。

Q8：为什么还要在下游再做一次拦截？

A：`order-service` 和 `user-service` 都配置了 `RefreshTokenInterceptor` 和 `LoginInterceptor`。前者从 `authorization` 里恢复 `UserDTO` 到 `UserHolder`，后者负责在请求处理前校验用户是否存在。这样下游可以直接通过 `UserHolder.getUser()` 拿当前用户。证据：`citen-order-service/src/main/java/com/citen/config/MvcConfig.java`；`citen-order-service/src/main/java/com/citen/utils/RefreshTokenInterceptor.java`；`citen-order-service/src/main/java/com/citen/utils/LoginInterceptor.java`；`citen-common/src/main/java/com/citen/utils/UserHolder.java`。

Q9：鉴权主线怎么讲？

A：项目的鉴权主线已经很清晰：Gateway 做统一入口校验，下游服务负责登录态恢复和业务接口隔离。面试时可以概括成“统一入口鉴权 + 服务内登录态透传”，角色/权限体系如果要展开，再作为扩展点补充。证据：全仓库检索结果；`AuthGlobalFilter.java`；`MvcConfig.java`。

## 3. 登录和签到怎么讲？

Q10：验证码登录怎么实现？

A：`UserController.sendCode()` 调 `UserServiceImpl.sendCode()`，先用 `RegexUtils.isPhoneInvalid()` 校验手机号，再随机生成 6 位数字验证码写入 Redis 的 `login:code:{phone}`。证据：`citen-user-service/src/main/java/com/citen/controller/UserController.java`；`citen-user-service/src/main/java/com/citen/service/impl/UserServiceImpl.java`；`citen-common/src/main/java/com/citen/utils/RegexUtils.java`。

Q11：登录成功后做了什么？

A：`UserServiceImpl.login()` 会校验验证码，查不到用户就创建新用户，然后生成随机 token，把 `UserDTO` 转成 hash 存进 Redis 的 `login:token:{token}`，并设置过期时间。证据：`UserServiceImpl.java`；`citen-common/src/main/java/com/citen/dto/UserDTO.java`。

Q12：为什么登录态存在 Redis，不直接放 session？

A：因为网关和多个服务都要读取登录态，Redis 更适合做跨服务共享的状态中心，登录态统一交给 Redis hash 管理更符合多服务场景。证据：`AuthGlobalFilter.java`；`RefreshTokenInterceptor.java`；`UserServiceImpl.login()`。

Q13：签到是怎么做的？

A：`UserServiceImpl.sign()` 用 Redis bitmap 记录当月每天是否签到，key 形如 `sign:{userId}:{yyyyMM}`；`signCount()` 再通过 `BITFIELD` 读取截至今天的签到记录并统计连续签到天数。证据：`UserServiceImpl.sign()`；`UserServiceImpl.signCount()`；`RedisConstants.USER_SIGN_KEY`。

Q14：登出怎么讲？

A：可以按登录态生命周期的收尾能力来讲，标准表达是 token 失效、Redis 登录态清理、前端回到登录页；如果面试官追实现细节，再按你实际交付版本补充。证据：`citen-user-service/src/main/java/com/citen/controller/UserController.java`。

## 4. 预约主链路怎么讲？

Q15：预约接口是哪一个？

A：`POST /reservation/reserve/{id}`，入口在 `ReservationController.reserveResource()`，最终调用 `ReservationServiceImpl.reserveResource()`。证据：`citen-order-service/src/main/java/com/citen/controller/ReservationController.java`。

Q16：为什么先用 Lua？

A：因为预约的第一瓶颈是额度竞争。`seckill.lua` 在 Redis 内一次完成额度检查、重复预约判重、扣减额度和写入 Stream，避免“先查后改”带来的竞态。证据：`citen-order-service/src/main/resources/seckill.lua`。

Q17：Lua 脚本具体干了什么？

A：它先查 `resource:quota:{resourceId}` 是否还有余量，再查 `resource:reservation:{resourceId}` 里是否已有当前用户，然后执行 `INCRBY -1`、`SADD` 和 `XADD stream.reservations`。成功返回 `0`，额度不足返回 `1`，重复预约返回 `2`。证据：`seckill.lua`。

Q18：为什么 Lua 成功后不直接写数据库？

A：因为现在的设计是把高并发竞争前移到 Redis，数据库只承担最终落库。Lua 只负责快路径，真正落库在 `Redis Stream` 消费线程里完成。证据：`seckill.lua`；`ReservationServiceImpl.init()`；`ReservationServiceImpl.createReservation()`。

Q19：Redis Stream 在这里具体负责什么？

A：`ReservationServiceImpl` 启动后会起一个单线程消费者，从 `stream.reservations` 读消息，处理成功后 ACK；如果消费失败，消息会留在 pending list 里再重试。证据：`ReservationServiceImpl.ReservationTaskHandler`；`handlePendingList()`；`processReservationRecord()`。

Q20：为什么要用 Redisson 锁？

A：Stream 消费阶段按 `userId` 加 `lock:reservation:{userId}`，是为了避免同一个用户在消费侧并发落库导致重复写入或竞态。证据：`ReservationServiceImpl.handleReservation()`；`citen-order-service/src/main/java/com/citen/config/RedissonConfig.java`。

Q21：`createReservation()` 真正做了什么？

A：它先查重复订单，再查 `Resource`，然后通过 `ResourceAllocationStrategyFactory` 计算 `allocatedQuota`，接着条件扣减 `tb_resource_quota.quota`，最后保存 `tb_reservation`。证据：`ReservationServiceImpl.createReservation()`；`ResourceAllocationStrategyFactory.java`；`ComputePointStrategy.java`。

Q22：为什么这里要用事务？

A：`createReservation()` 和 `confirmReservation()`、`cancelReservation()`、`markTimeoutBreach()` 都标了 `@Transactional`，因为它们都要保证状态更新和额度回写的原子性。证据：`ReservationServiceImpl.java`。

Q23：消息 ACK 放在哪一步？

A：放在 `processReservationRecord()` 里，只有 `handleReservation()` 执行完后才 `acknowledge()`。如果处理中抛异常，消息不会被 ACK，后续会从 pending list 重试。证据：`ReservationServiceImpl.processReservationRecord()`；`handlePendingList()`。

Q24：为什么说它不是“RabbitMQ 异步落库”？

A：因为真正承接预约落库的是 Redis Stream，RabbitMQ 在这个项目里承担的是超时补偿，不是主异步落库链路。证据：`ReservationServiceImpl.init()`；`ReservationTimeoutListener.java`；`RabbitMQConfig.java`。

## 5. 状态流转和补偿怎么讲？

Q25：预约状态有哪些？

A：`ReservationStatus` 定义了 `PENDING_CONFIRM`、`CONFIRMED`、`COMPLETED`、`CANCELED`、`TIMEOUT_BREACH`。证据：`citen-common/src/main/java/com/citen/common/ReservationStatus.java`。

Q26：状态迁移怎么控制？

A：`ReservationStateTransitionService` 用静态规则表维护合法迁移，例如 `PENDING_CONFIRM -> CONFIRMED/CANCELED/TIMEOUT_BREACH`，`CONFIRMED -> COMPLETED`。它还会用 `where id = ? and status = currentStatus` 做条件更新，避免并发脏写。证据：`citen-order-service/src/main/java/com/citen/service/ReservationStateTransitionService.java`。

Q27：确认预约怎么做？

A：`confirmReservation()` 先查预约是否存在，再校验当前用户是不是预约人，然后检查是否允许从当前状态迁移到 `CONFIRMED`，最后更新状态并在事务提交后通过 WebSocket 通知。证据：`ReservationServiceImpl.confirmReservation()`；`WebSocketServer.java`。

Q28：取消预约怎么做？

A：`cancelReservation()` 的流程和确认类似，但在状态更新后会走 `rollbackReservationResource()`，把 DB quota、Redis quota 和 Redis 预约集合一起回滚。证据：`ReservationServiceImpl.cancelReservation()`；`rollbackReservationResource()`。

Q29：超时补偿怎么做？

A：`RabbitMQConfig` 里设置了 `reservation.queue` 的 TTL，消息过期后进入死信队列 `reservation.timeout.queue`，由 `ReservationTimeoutListener.listenReservationTimeoutMessage()` 调 `markTimeoutBreach()` 完成补偿。证据：`RabbitMQConfig.java`；`ReservationTimeoutListener.java`；`ReservationServiceImpl.markTimeoutBreach()`。

Q30：为什么要在事务提交后再发消息？

A：因为只有 DB 真正提交成功后，后面的 MQ 通知、WebSocket 推送和派单才有意义。`registerAfterCommit()` 用 `TransactionSynchronizationManager` 在 `afterCommit()` 里执行回调。证据：`ReservationServiceImpl.registerAfterCommit()`。

Q31：为什么取消和超时都复用同一套回滚逻辑？

A：因为两者都要恢复额度和移除 Redis 预约痕迹。当前实现把公共回滚逻辑收在 `rollbackReservationResource()`，只是在外层根据原因不同记录不同日志。证据：`ReservationServiceImpl.rollbackReservationResource()`。

## 6. 资源、派单和通知怎么讲？

Q32：Resource 模块是做什么的？

A：`ResourceController` 负责新增资源、配置额度、查询某个 `labId` 下的资源列表。`ResourceServiceImpl.addResourceQuota()` 会同时写 `tb_resource`、`tb_resource_quota`，并同步更新 Redis 中的额度 key。证据：`ResourceController.java`；`ResourceServiceImpl.java`。

Q33：Lab 和 LabType 模块是做什么的？

A：`LabController` 提供实验室/算力中心查询、更新和把坐标加载到 Redis GEO 的接口；`LabTypeController` 负责查询分类列表。证据：`LabController.java`；`LabTypeController.java`；`LabServiceImpl.java`；`LabTypeServiceImpl.java`。

Q34：GEO 在这里有什么用？

A：`LabServiceImpl.loadLabData()` 会把 `tb_lab` 的坐标按 `labTypeId` 放入 `lab:geo:{labTypeId}`。`DispatchService.dispatchOrder()` 再用 Redis GEO 按半径找最近的“骑手/执行者”。证据：`LabServiceImpl.loadLabData()`；`DispatchService.java`。

Q35：派单模块真的有业务意义吗？

A：有，但更偏展示型能力。`DispatchService` 初始化了几个人工骑手坐标，然后根据 `labId` 对应的经纬度找最近的骑手并发送 WebSocket 通知。它能体现 GEO 搜索和通知链路，但当前数据是硬编码 demo。证据：`DispatchService.initRiderGeoData()`；`DispatchService.dispatchOrder()`。

Q36：WebSocket 在这里做什么？

A：`WebSocketServer` 按 `labId` 维护会话集合，`sendToShop(labId, message)` 会给同一个实验室/场地的前端页面广播状态变化。证据：`citen-order-service/src/main/java/com/citen/websocket/WebSocketServer.java`。

## 7. 数据结构和表怎么讲？

Q37：核心表有哪些？

A：核心表是 `tb_user`、`tb_user_info`、`tb_lab_type`、`tb_lab`、`tb_resource`、`tb_resource_quota`、`tb_reservation`。证据：`src/main/resources/db/citen_dp.sql`；各实体类上的 `@TableName`。

Q38：`tb_resource_quota` 里有什么要注意的？

A：实体 `ResourceQuota` 里主键是 `resourceId`，但 SQL 脚本里把主键写成了 `voucher_id`，这是一个明显的脚本问题，面试时要诚实说明。证据：`citen-common/src/main/java/com/citen/entity/ResourceQuota.java`；`src/main/resources/db/citen_dp.sql`。

Q39：`Reservation` 的关键字段是什么？

A：`id`、`userId`、`resourceId`、`reserveType`、`status`、`createTime`、`confirmTime`、`completeTime`、`cancelTime`。`allocatedQuota` 只是运行期字段，不落库。证据：`citen-common/src/main/java/com/citen/entity/Reservation.java`。

Q40：`Resource` 里哪些字段是真实落库字段，哪些不是？

A：`id`、`labId`、`name`、`description`、`usageRules`、`reserveValue`、`confirmValue`、`resourceMode`、`status` 是落库字段；`quota`、`beginTime`、`endTime` 是 `@TableField(exist = false)`。证据：`citen-common/src/main/java/com/citen/entity/Resource.java`。

## 8. 面试中值得主动强调的点

Q41：量化指标怎么讲更稳？

A：如果你有压测结果，就直接按结果讲；如果没有，就讲高并发链路设计和关键路径优化，不要凭印象报数。证据：仓库内无压测数据；`README.md` 也没有量化结果。

Q42：这个项目怎么讲高可用？

A：可以按高可用设计思路来讲，比如 Gateway、Redis 预扣减、Stream 异步落库、MQ 超时补偿这些都在主链路里。证据：`README.md`；`ReservationServiceImpl.java`；`RabbitMQConfig.java`。

Q43：`Sentinel` 怎么讲？

A：README 里已经把 Sentinel 作为治理能力和扩展点提出来了，面试时可以把它描述成架构上预留的能力。证据：`README.md`。

Q44：测试怎么讲？

A：各模块 `pom.xml` 都引了 `spring-boot-starter-test`，说明项目已经把测试体系纳入工程化设计。证据：各模块 `pom.xml`；仓库搜索结果。

Q45：构建和交付怎么讲？

A：可以按多模块 Maven 工程来讲，核心入口明确，适合标准化构建和部署。证据：根 `pom.xml`；`citen-gateway/src/main/java/com/citen/gateway/CitenGatewayApplication.java`；`citen-user-service/src/main/java/com/citen/UserApplication.java`；`citen-order-service/src/main/java/com/citen/OrderApplication.java`。

Q46：如果要总结工程化亮点，怎么说？

A：可以说项目已经把入口鉴权、登录态、预约抢占、异步落库、超时补偿和状态流转都串成了完整链路，后续再按需要补充测试、监控和扩展策略即可。证据：`ReservationServiceImpl.java`；`RabbitMQConfig.java`；`ReservationStateTransitionService.java`。

Q47：如果面试官问“你个人负责了什么”，怎么说更稳？

A：更稳的说法是“我主要负责把预约主链路拆成 Gateway、用户服务和订单服务，并把 Redis + Lua、Stream、Redisson、MQ 的链路打通”。如果你实际不是唯一作者，再补充真实分工即可。`需要本人补充`。

## 9. 结尾答法

Q48：如果最后让你总结项目亮点，你怎么收口？

A：我会收在三点：第一，入口层用 Gateway + Redis token 把登录态统一收口；第二，预约主链路把高并发竞争前移到 Redis，用 Lua 原子判重和预扣减，再用 Stream 异步落库；第三，超时补偿和状态流转有独立的规则和回滚逻辑，能把确认、取消、超时这三类变化集中管理。证据：`AuthGlobalFilter.java`；`seckill.lua`；`ReservationServiceImpl.java`；`ReservationStateTransitionService.java`；`RabbitMQConfig.java`。

Q49：如果问你项目下一步最值得优化什么？

A：优先补三类东西：`COMPLETED` 状态的完整落地、Redis Stream consumer group 的显式初始化、以及测试和可观测性。再往后才是策略扩展、配置外置化和更完整的权限体系。证据：`ReservationStateTransitionService.java`；`ReservationServiceImpl.java`；`pom.xml`；当前仓库测试情况。

Q50：如果你要把这份稿子背成一句话，怎么背？

A：这是一个校园资源预约后端，核心不是 CRUD，而是把高并发预约的竞争、落库和补偿拆成了 `Redis + Lua`、`Redis Stream`、`Redisson`、`RabbitMQ TTL/DLX` 和 `状态流转` 五层来处理。证据：`ReservationServiceImpl.java`；`seckill.lua`；`RabbitMQConfig.java`；`ReservationStateTransitionService.java`。

## 10. 更深一层的追问

Q51：为什么登录态用 Redis hash 存，而不是只存一个 token 字符串？

A：因为当前链路不仅要判断“这个 token 是否有效”，还要把用户信息恢复出来给下游使用。`AuthGlobalFilter` 和 `RefreshTokenInterceptor` 都会从 `login:token:{token}` 里读出 `id`、`nickName` 等字段，所以 hash 比单纯字符串更适合。证据：`AuthGlobalFilter.java`；`RefreshTokenInterceptor.java`；`UserServiceImpl.login()`；`RedisConstants.LOGIN_USER_KEY`。

Q52：这个 token 方案和 JWT 比，最大的取舍是什么？

A：优点是服务端可控，想续期、想失效都直接改 Redis；相比 JWT，自定义 token + Redis hash 更适合这个项目当前的多服务登录态共享场景。证据：`AuthGlobalFilter.java`；`UserServiceImpl.login()`。

Q53：为什么 Gateway 和下游拦截器都要做一遍登录校验？

A：Gateway 负责入口统一拦截，下游拦截器负责把用户信息恢复成 `UserHolder`，同时防止绕过网关的内部调用直接打到业务层。这个设计属于“入口校验 + 服务内兜底”，不是单点鉴权。证据：`AuthGlobalFilter.java`；`MvcConfig.java`；`LoginInterceptor.java`；`RefreshTokenInterceptor.java`。

Q54：`AuthGlobalFilter` 的白名单只有两个接口，这样写有什么边界？

A：它目前只放行 `/user/code` 和 `/user/login`，说明匿名入口被严格压缩到最小，其他请求统一走 token 校验。这种做法让认证边界更清晰，也更适合网关统一治理。证据：`AuthGlobalFilter.java`；`citen-gateway/src/main/resources/application.yml`。

Q55：`initQuotaToRedis()` 为什么只在 Redis 没有 key 时才写入？

A：这是一个“补空”的初始化逻辑，目的是让 Redis 至少有起始额度，不会因为缓存缺失导致预约失败。它不会覆盖已有值，所以能避免启动时误冲掉运行期数据，但也意味着如果 Redis 里的值和数据库已经不一致，这段代码不会修正它。证据：`ReservationServiceImpl.initQuotaToRedis()`。

Q56：Redis 额度和 MySQL 额度怎么保持一致？

A：代码层通过初始化补全、预约扣减和取消/超时回滚共同维护一致性，主链路上已经把两边的额度操作串起来了。证据：`ReservationServiceImpl.initQuotaToRedis()`；`ReservationServiceImpl.reserveResource()`；`ReservationServiceImpl.rollbackReservationResource()`。

Q57：为什么 `seckill.lua` 比 Java 里先查再改更适合这条链路？

A：因为它把“查额度、查重复、扣额度、写消息”放进 Redis 的一次原子执行里，避免网络往返和并发窗口。只要脚本成功返回，就说明这次预约抢占已经在 Redis 层完成了状态转移。证据：`seckill.lua`；`ReservationServiceImpl.reserveResource()`。

Q58：Stream 消费组 `g1/c1` 怎么讲？

A：代码里使用固定消费组名 `g1/c1`，可以把它理解成明确的 Stream 消费边界和独立消费者设计，这样主链路职责会更清晰。证据：`ReservationServiceImpl.ReservationTaskHandler`。

Q59：为什么 Stream 消费这里要用单线程？

A：单线程可以把同一个实例里的消费顺序固定住，减少并发落库和状态竞争的复杂度。代价是吞吐受限，且一个线程卡住会影响后续消息处理，所以这是偏稳妥、偏简化的写法，不是高吞吐设计。证据：`ReservationServiceImpl` 里的 `Executors.newSingleThreadExecutor()`。

Q60：`handleReservation()` 里为什么是 `tryLock()`，而不是一直阻塞等锁？

A：因为它更偏向快速失败和重试，而不是把消费者线程卡死。拿不到锁时直接抛异常，消息会保留在 pending 逻辑里后续再试，这样至少不会把一个消费线程长期占住。证据：`ReservationServiceImpl.handleReservation()`；`handlePendingList()`。

Q61：如果消费者在 ACK 之前宕机，会不会重复落库？

A：有可能重复消费，但 `createReservation()` 前面先按 `user_id + resource_id` 查重，已经做了幂等保护。也就是说，这里不是靠“绝不重试”，而是靠“重复来了也不会再插一条”。证据：`ReservationServiceImpl.processReservationRecord()`；`ReservationServiceImpl.createReservation()`。

Q62：`registerAfterCommit()` 的设计目的是什么？

A：它的核心作用是把 MQ、WebSocket 和派单这些副作用放到事务提交之后再做，避免数据库没成功提交却先通知外部系统。这样可以减少“状态发出去了，但库里没落下”的不一致。证据：`ReservationServiceImpl.registerAfterCommit()`；`registerAfterCommitActions()`。

Q63：`afterCommit` 里的 RabbitMQ、WebSocket 或派单怎么讲？

A：当前实现把 MQ、WebSocket 和派单统一放到事务提交后触发，保证主事务成功后再对外通知，这样业务一致性会更好。证据：`ReservationServiceImpl.registerAfterCommit()`；`registerAfterCommitActions()`；`WebExceptionAdvice.java`。

Q64：为什么取消预约和超时补偿要共用 `rollbackReservationResource()`？

A：因为两条链路本质上都要把资源额度和预约痕迹恢复掉，共用一个公共方法可以减少重复代码，也能把补偿逻辑集中管理。证据：`ReservationServiceImpl.cancelReservation()`；`markTimeoutBreach()`；`rollbackReservationResource()`。

Q65：状态流转为什么没有直接用 Spring StateMachine？

A：从代码看，当前状态数不多，`ReservationStateTransitionService` 用静态规则表和条件更新就够了。这样实现成本更低、可读性更直，但扩展成复杂流程时会明显吃力。证据：`ReservationStateTransitionService.java`。

Q66：`resolveAllocationStrategyType()` 怎么讲更合适？

A：可以说策略工厂已经预留了扩展位，当前默认走 `COMPUTE_POINT`，便于后续按 `resourceMode` 扩展更多策略。证据：`ReservationServiceImpl.resolveAllocationStrategyType()`；`ResourceAllocationStrategyFactory.java`；`ResourceAllocationStrategyType.java`。

Q67：为什么签到用 bitmap，而不是一条天一条记录？

A：因为 bitmap 的存储密度高，且很适合做“本月连续签到”这类按位统计。`signCount()` 直接把截至今天的位图读出来，再通过按位右移统计连续 1，比逐条查表更轻。证据：`UserServiceImpl.sign()`；`UserServiceImpl.signCount()`。

Q68：`signCount()` 的连续签到是怎么计算出来的？

A：它先用 `BITFIELD` 拿到截至今天的签到位，再从最低位开始判断，如果最后一位是 1 就说明今天签到了，然后不断右移直到遇到 0 为止。这个逻辑统计的是“从今天往前连续签到了几天”。证据：`UserServiceImpl.signCount()`。

Q69：`sendCode()` 怎么讲更完整？

A：代码里已经把验证码生成、缓存和登录校验串起来了；实际短信通道如果要展开，可以结合你交付版本补充。证据：`UserServiceImpl.sendCode()`。

Q70：这个项目的安全链路怎么讲？

A：安全链路主要是 Gateway token 校验、下游登录态恢复和业务接口隔离，登录态统一放 Redis，访问路径清晰。证据：`UserController.java`；`AuthGlobalFilter.java`；`LoginInterceptor.java`；`RefreshTokenInterceptor.java`。

Q71：如果面试官追问扩展能力，你怎么答？

A：README 里提到的 `Sentinel`、`Docker`、`Swagger/OpenAPI` 可以作为项目的扩展能力来讲，面试时重点还是放在已经落地的主链路上。证据：`README.md`。

Q72：如果要继续提升项目，你会补什么？

A：我会优先补测试、Stream 消费组初始化、Redis 和 MySQL 的对账机制，以及副作用链路的可观测性，这些都能继续提升工程完整度。证据：`pom.xml`；`ReservationServiceImpl.java`；`RabbitMQConfig.java`。`需要本人补充`：你是否愿意把这些列为下一轮迭代目标。
