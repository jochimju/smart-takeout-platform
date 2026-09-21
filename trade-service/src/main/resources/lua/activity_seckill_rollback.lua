-- 仅当本次事务已经把用户标记为已购时才归还一个库存。
if redis.call('srem', KEYS[2], ARGV[1]) == 1 then
    return redis.call('incr', KEYS[1])
end
return 0
