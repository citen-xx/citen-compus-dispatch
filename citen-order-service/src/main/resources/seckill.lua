-- 参数列表
-- 资源ID
local resourceId = ARGV[1]
-- 用户ID
local userId = ARGV[2]
-- 预约记录ID
local reservationId = ARGV[3]

-- Redis Key
-- 资源额度Key
local quotaKey = 'resource:quota:' .. resourceId
-- 预约资格Key
local reservationKey = 'resource:reservation:' .. resourceId

-- 算力/座位额度校验
local quota = redis.call('get', quotaKey)
if (quota == false or tonumber(quota) == nil or tonumber(quota) <= 0) then
    return 1
end

-- 判断用户是否已经预约过该资源
if (redis.call('sismember', reservationKey, userId) == 1) then
    return 2
end

-- 扣减资源额度
redis.call('incrby', quotaKey, -1)

-- 记录预约资格
redis.call('sadd', reservationKey, userId)

-- 写入预约消息流
redis.call(
    'xadd',
    'stream.reservations',
    '*',
    'userId', tostring(userId),
    'resourceId', tostring(resourceId),
    'id', tostring(reservationId)
)

return 0
