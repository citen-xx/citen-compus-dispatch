-- 1. 参数列表
-- 1.1. 资源 id
local resourceId = ARGV[1]
-- 1.2. 用户 id
local userId = ARGV[2]
-- 1.3. 预约记录 id
local reservationId = ARGV[3]

-- 2. 数据 key
-- 2.1. 资源额度 key
local quotaKey = 'resource:quota:' .. resourceId
-- 2.2. 资源预约记录 key
local reservationKey = 'resource:reservation:' .. resourceId

-- 3. 脚本业务
-- 3.1. 判断算力/座位额度是否充足
local quota = redis.call('get', quotaKey)
if (quota == false or tonumber(quota) == nil or tonumber(quota) <= 0) then
    return 1
end

-- 3.2. 判断用户是否已经抢占过该资源
if (redis.call('sismember', reservationKey, userId) == 1) then
    return 2
end

-- 3.3. 扣减额度
redis.call('incrby', quotaKey, -1)
-- 3.4. 记录抢占资格
redis.call('sadd', reservationKey, userId)
-- 3.5. 发送异步消息，后续创建预约记录
redis.call(
    'xadd',
    'stream.reservations',
    '*',
    'userId', tostring(userId),
    'resourceId', tostring(resourceId),
    'id', tostring(reservationId)
)

return 0
