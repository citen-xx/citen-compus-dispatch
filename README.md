# citen-dp

`citen-dp` 是一个基于 Spring Cloud Alibaba 的本地生活交易系统示例项目，核心覆盖用户登录、网关鉴权、优惠券秒杀下单、异步订单处理、订单超时取消、骑手派单和商家实时通知等典型业务链路。

这个项目最初是单体 Spring Boot 结构，当前已经重构为 Maven 多模块微服务架构，适合用来展示下面这些能力：

- 单体到微服务的拆分与模块边界设计
- Gateway 统一入口和无状态鉴权
- Redis 在登录态、Bitmap、Lua、Stream、GEO 等多场景中的使用
- RabbitMQ 延迟消息 + 死信队列处理订单超时取消
- Redisson 分布式锁控制并发重复下单
- OpenFeign 跨服务调用
- WebSocket 实时通知

---

## 1. 项目架构

当前项目包含 5 个核心模块：

```text
citen-dp
├─ citen-common         公共模块：实体、DTO、常量、工具类
├─ citen-gateway        网关服务：统一入口、路由转发、Token 鉴权
├─ citen-user-api       用户服务 API：OpenFeign 接口定义
├─ citen-user-service   用户微服务：验证码登录、用户查询、签到
├─ citen-order-service  订单微服务：秒杀下单、异步创建、超时取消、派单
└─ src                  单体阶段的历史代码/迁移参考代码
```

服务关系如下：

```text
Client
  |
  v
Gateway (8080)
  |--------------------> user-service (8081)
  |
  └--------------------> order-service (8082)
                              |
                              |---- MySQL
                              |---- Redis
                              |---- RabbitMQ
                              └---- WebSocket
```

---

## 2. 技术栈

### 后端框架

- Spring Boot 2.6.13
- Spring Cloud 2021.0.5
- Spring Cloud Alibaba 2021.0.5.0

### 微服务与网关

- Nacos：服务注册与发现
- Spring Cloud Gateway：网关路由与统一鉴权
- OpenFeign：跨服务调用

### 数据与中间件

- MySQL 8
- Redis
- RabbitMQ
- Redisson

### ORM 与工具

- MyBatis-Plus 3.4.3
- Hutool
- Lombok

### 其他

- WebSocket：实时通知
- Maven 多模块父子工程

---

## 3. 核心业务能力

### 3.1 用户验证码登录

- 客户端请求 `/user/code`
- 用户服务校验手机号格式
- 验证码写入 Redis，设置过期时间
- 客户端请求 `/user/login`
- 用户服务校验验证码
- 首次登录自动注册用户
- 生成 Token，将 `UserDTO` 以 Hash 形式写入 Redis
- 返回 Token，后续请求通过 `authorization` 请求头携带

### 3.2 网关统一鉴权

网关模块通过 `GlobalFilter` 实现统一鉴权：

- 白名单接口直接放行：`/user/code`、`/user/login`
- 其他请求统一校验 `authorization` Token
- 基于 Redis 查询 `login:token:{token}`
- 无 Token 或 Token 失效时直接返回 `401`
- 鉴权成功后刷新 TTL，并透传用户信息请求头到下游服务

当前网关路由规则：

| 路径 | 路由目标 |
|---|---|
| `/user/**` | `lb://user-service` |
| `/voucher-order/**` | `lb://order-service` |

### 3.3 秒杀下单链路

订单服务的秒杀流程是这个项目最核心的亮点之一：

1. 用户请求秒杀接口
2. 服务生成全局订单 ID
3. 执行 Redis Lua 脚本，原子完成：
   - 校验库存
   - 校验一人一单
   - 扣减库存
   - 写入 Redis Stream
4. 主线程快速返回 `orderId`
5. 异步消费者从 `stream.orders` 消费订单消息
6. 使用 Redisson 锁按 `userId` 做并发控制
7. 事务内完成订单落库与数据库库存扣减
8. 事务提交后触发：
   - RabbitMQ 延迟消息
   - 派单逻辑
   - WebSocket 商家通知

### 3.4 订单超时取消

- 下单成功后发送 RabbitMQ 延迟消息
- 消息到期后进入死信队列
- 监听器消费超时消息
- 未支付订单自动取消
- 同步恢复数据库库存与 Redis 库存
- 推送订单状态变更消息

### 3.5 骑手派单与实时通知

- Redis GEO 存储骑手坐标
- 订单创建成功后按门店坐标搜索最近骑手
- 派单完成后通过 WebSocket 通知商家端

---

## 4. 模块说明

### citen-common

公共模块，主要内容包括：

- Entity 实体类
- DTO
- Redis 常量
- 系统常量
- 正则校验工具
- 用户上下文工具

### citen-user-service

用户服务，负责：

- 发送验证码
- 登录
- 查询用户信息
- 用户签到
- 连续签到统计

默认端口：`8081`

### citen-user-api

用户服务对外 API 模块，当前主要提供：

- `UserClient`

用于其他服务通过 OpenFeign 调用用户服务。

### citen-order-service

订单服务，负责：

- 优惠券秒杀下单
- 订单异步消费
- 库存扣减
- 订单超时取消
- 派单
- 商家通知

默认端口：`8082`

### citen-gateway

网关服务，负责：

- 统一入口
- 服务路由
- Token 鉴权
- 无状态访问控制

默认端口：`8080`

---

## 5. 当前服务配置

### gateway-service

- 端口：`8080`
- 路由：
  - `/user/** -> user-service`
  - `/voucher-order/** -> order-service`

### user-service

- 端口：`8081`
- 数据库：`citen_dp`

### order-service

- 端口：`8082`
- 数据库：`citen_dp`
- 依赖 Redis、RabbitMQ

### 注册中心

- Nacos：`127.0.0.1:8848`

---

## 6. 快速启动

### 6.1 环境准备

请先准备以下依赖：

- JDK 8
- Maven 3.9+
- MySQL 8
- Redis
- RabbitMQ
- Nacos

### 6.2 数据库

当前配置使用数据库：

```text
citen_dp
```

项目中保留了 SQL 文件：

```text
src/main/resources/db/citen_dp.sql
```

### 6.3 启动顺序

建议按下面顺序启动：

1. Nacos
2. Redis
3. RabbitMQ
4. `citen-user-service`
5. `citen-order-service`
6. `citen-gateway`

### 6.4 编译

```bash
mvn clean compile -DskipTests
```

### 6.5 单独启动模块

可分别启动以下主类：

- `com.citen.UserApplication`
- `com.citen.OrderApplication`
- `com.citen.gateway.CitenGatewayApplication`

---

## 7. 项目亮点

这个项目适合用来展示以下工程与业务能力：

- 从单体应用重构为微服务架构
- 抽离公共模块与 API 模块，降低耦合
- 使用 Gateway 做统一鉴权
- 使用 Redis Lua 实现秒杀原子校验
- 使用 Redis Stream 做异步削峰
- 使用 Redisson 解决高并发重复下单问题
- 使用 RabbitMQ 处理订单超时取消
- 使用事务提交后回调控制外部副作用时机
- 使用 Redis Bitmap 做签到统计
- 使用 Redis GEO 做派单
- 使用 WebSocket 做实时通知
- 使用策略模式抽象不同优惠券价格计算逻辑

---

## 8. 适合面试展开的点

如果你是把这个项目用于简历或面试，这几个点最值得展开：

- 为什么要从单体拆到微服务
- 为什么把鉴权前移到网关
- 为什么秒杀要先走 Redis Lua，而不是直接查数据库
- 为什么异步下单用 Redis Stream
- pending-list 的意义是什么
- Redisson 锁在这里解决了什么问题
- RabbitMQ 延迟队列和死信队列是怎么配合的
- 为什么库存恢复要同时恢复 MySQL 和 Redis
- 为什么使用 Bitmap、GEO、WebSocket
- Feign API 模块为什么要单独抽出来

项目内已经额外整理了一份更详细的面试材料：

```text
Introduction.docx
```

---

## 9. 后续可优化方向

- 把下游服务中的重复鉴权逻辑继续收敛，只保留网关统一鉴权
- 引入 Nacos 配置中心而不是全部写在本地 `application.yml`
- 增加限流、熔断、降级能力
- 增加链路追踪和监控告警
- 补充更完整的自动化测试与压测脚本
- 继续把 `src` 中残留的单体历史代码彻底清理或完全迁移

---

## 10. 说明

这是一个以工程演进和核心链路设计为重点的项目，当前代码重点展示的是：

- 微服务拆分思路
- 高并发秒杀处理链路
- 网关统一鉴权
- 中间件组合使用能力

如果你希望，我还可以继续补下面这些内容：

- GitHub 首页英文版 README
- README 中的架构图图片版
- 接口文档章节
- 部署章节（Docker / Docker Compose）
- 更偏“简历展示风格”的精简版首页介绍
