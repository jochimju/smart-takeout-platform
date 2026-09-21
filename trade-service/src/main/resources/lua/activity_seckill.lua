-- 套餐秒杀原子校验脚本
-- KEYS[1] 活动资格 Hash：status、setmealId、beginTime、endTime
-- KEYS[2] 活动库存 String
-- KEYS[3] 活动已购用户 Set
-- ARGV[1] 用户 ID，ARGV[2] 套餐 ID，ARGV[3] 当前毫秒时间戳
-- 返回：1 成功；0 库存不足；-1 重复购买；-2 活动不存在/未启用；-3 套餐不匹配；-4 未开始；-5 已结束

local activityKey = KEYS[1]
local stockKey = KEYS[2]
local userKey = KEYS[3]
local userId = ARGV[1]
local setmealId = ARGV[2]
local now = tonumber(ARGV[3])

local status = redis.call('hget', activityKey, 'status')
if not status or status ~= '1' then
    return -2
end

if redis.call('hget', activityKey, 'setmealId') ~= setmealId then
    return -3
end

local beginTime = tonumber(redis.call('hget', activityKey, 'beginTime'))
local endTime = tonumber(redis.call('hget', activityKey, 'endTime'))
if not beginTime or now < beginTime then
    return -4
end
if not endTime or now > endTime then
    return -5
end

if redis.call('sismember', userKey, userId) == 1 then
    return -1
end

local stock = tonumber(redis.call('get', stockKey))
if not stock or stock <= 0 then
    return 0
end

redis.call('decr', stockKey)
redis.call('sadd', userKey, userId)
return 1
