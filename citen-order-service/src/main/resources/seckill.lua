-- KEYS[1] resource quota key
-- KEYS[2] resource/date occupied-minute counter hash
-- KEYS[3] user/resource/date occupied-minute bitmap
-- KEYS[4] reservation metadata key
-- KEYS[5] reservation stream key
-- ARGV: userId, resourceId, reservationId, date, startMinute, endMinute, ttlSeconds, startTime, endTime

local quotaKey = KEYS[1]
local slotsKey = KEYS[2]
local userSlotsKey = KEYS[3]
local metaKey = KEYS[4]
local streamKey = KEYS[5]

local userId = ARGV[1]
local resourceId = ARGV[2]
local reservationId = ARGV[3]
local reservationDate = ARGV[4]
local startMinute = tonumber(ARGV[5])
local endMinute = tonumber(ARGV[6])
local ttlSeconds = tonumber(ARGV[7])

if startMinute == nil or endMinute == nil or startMinute < 0 or endMinute > 1440 or startMinute >= endMinute then
    return 4
end

if redis.call('exists', metaKey) == 1 then
    return 3
end

local quota = tonumber(redis.call('get', quotaKey))
if quota == nil or quota <= 0 then
    return 1
end

for minute = startMinute, endMinute - 1 do
    if redis.call('getbit', userSlotsKey, minute) == 1 then
        return 3
    end
    local occupiedValue = redis.call('hget', slotsKey, tostring(minute))
    if occupiedValue ~= false and tonumber(occupiedValue) == nil then
        return 5
    end
    local occupied = tonumber(occupiedValue) or 0
    if occupied >= quota then
        return 2
    end
end

for minute = startMinute, endMinute - 1 do
    redis.call('hincrby', slotsKey, tostring(minute), 1)
    redis.call('setbit', userSlotsKey, minute, 1)
end

redis.call('hset', metaKey,
    'state', 'PREDEDUCTED',
    'userId', userId,
    'resourceId', resourceId,
    'reservationDate', reservationDate,
    'startMinute', startMinute,
    'endMinute', endMinute)

local currentSlotsTtl = redis.call('ttl', slotsKey)
if ttlSeconds > 0 and currentSlotsTtl < ttlSeconds then
    redis.call('expire', slotsKey, ttlSeconds)
end
local currentUserSlotsTtl = redis.call('ttl', userSlotsKey)
if ttlSeconds > 0 and currentUserSlotsTtl < ttlSeconds then
    redis.call('expire', userSlotsKey, ttlSeconds)
end
if ttlSeconds > 0 then
    redis.call('expire', metaKey, ttlSeconds)
end

local streamResult = redis.pcall('xadd', streamKey, 'MAXLEN', '~', 10000, '*',
    'userId', userId,
    'resourceId', resourceId,
    'id', reservationId,
    'reservationDate', reservationDate,
    'startTime', ARGV[8],
    'endTime', ARGV[9])

if type(streamResult) == 'table' and streamResult.err then
    for minute = startMinute, endMinute - 1 do
        local field = tostring(minute)
        local occupied = tonumber(redis.call('hget', slotsKey, field)) or 0
        if occupied > 1 then
            redis.call('hincrby', slotsKey, field, -1)
        else
            redis.call('hdel', slotsKey, field)
        end
        redis.call('setbit', userSlotsKey, minute, 0)
    end
    redis.call('del', metaKey)
    return 5
end

return 0
