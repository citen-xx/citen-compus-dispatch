-- KEYS[1] resource/date occupied-minute counter hash
-- KEYS[2] user/resource/date occupied-minute bitmap
-- KEYS[3] reservation metadata key
-- ARGV[1] reservation ID

if redis.call('exists', KEYS[3]) == 0 then
    return -2
end

if redis.call('hget', KEYS[3], 'state') == 'COMPENSATED' then
    return 0
end

local startMinute = tonumber(redis.call('hget', KEYS[3], 'startMinute'))
local endMinute = tonumber(redis.call('hget', KEYS[3], 'endMinute'))
if startMinute == nil or endMinute == nil then
    return -1
end

for minute = startMinute, endMinute - 1 do
    local field = tostring(minute)
    local occupied = tonumber(redis.call('hget', KEYS[1], field)) or 0
    if occupied > 1 then
        redis.call('hincrby', KEYS[1], field, -1)
    elseif occupied == 1 then
        redis.call('hdel', KEYS[1], field)
    end
    redis.call('setbit', KEYS[2], minute, 0)
end
redis.call('hset', KEYS[3], 'state', 'COMPENSATED', 'compensatedReservationId', ARGV[1])
redis.call('expire', KEYS[3], 604800)
return 1
