-- All keys share {activityId}. No writes until argument/type/metadata checks finish.
-- KEYS: meta, stock, users, requests, commands, released, outstanding, buckets
-- ARGV: user, requestId, hash, orderNumber, setmeal, command JSON, maxPending, maxOutstanding
redis.replicate_commands()
local function kind(key)
    local t = redis.call('type', key)
    return type(t) == 'table' and t.ok or t
end
for i=1,#KEYS do
    local t=kind(KEYS[i])
    local expected=i==2 and 'string' or 'hash'
    if t~='none' and t~=expected then return {'ERROR','缓存类型错误，请联系管理员'} end
end
local field=ARGV[1]..':'..ARGV[2]
local prior=redis.call('hget',KEYS[4],field)
if prior then
    local p=cjson.decode(prior)
    if p.requestHash~=ARGV[3] then return {'ERROR','请求编号已被其他参数使用'} end
    return {'OK',prior}
end
local m=redis.call('hmget',KEYS[1],'ready','status','setmeal','begin','end','price','admissionReady','outstandingCount')
local stock=tonumber(redis.call('get',KEYS[2]))
local begin=tonumber(m[4]) local finish=tonumber(m[5])
if m[1]~='1' or m[7]~='1' or not tonumber(m[8]) or tonumber(m[8])~=redis.call('hlen',KEYS[7]) or not tonumber(ARGV[8]) or tonumber(ARGV[8])<1 or not stock or stock<0 or stock~=math.floor(stock)
    or not begin or not finish or not tonumber(m[6]) then
    return {'ERROR','秒杀缓存未就绪，请稍后重试'}
end
if m[2]~='1' then return {'ERROR','秒杀活动未启用'} end
if m[3]~=ARGV[5] then return {'ERROR','套餐不属于该秒杀活动'} end
local t=redis.call('time')
local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
if now<begin then return {'ERROR','秒杀尚未开始'} end
if now>=finish then return {'ERROR','秒杀已结束'} end
if redis.call('hexists',KEYS[3],ARGV[1])==1 then return {'ERROR','您已抢购过该秒杀活动'} end
if stock<=0 then return {'ERROR','库存不足'} end
if redis.call('hlen',KEYS[5])>=tonumber(ARGV[7]) then return {'ERROR','秒杀排队人数过多，请稍后重试'} end
if redis.call('hlen',KEYS[7])>=tonumber(ARGV[8]) then return {'BUSY','秒杀建单繁忙，请稍后重试'} end
-- Global user reservation remains activity-wide. Buckets only partition MySQL writers.
local buckets=tonumber(redis.call('hget',KEYS[1],'bucketCount') or '1')
if buckets~=1 and buckets~=2 and buckets~=4 and buckets~=8 then return {'ERROR','库存桶未就绪'} end
local bucket=0
if buckets>1 then
    if not KEYS[8] or redis.call('hlen',KEYS[8])~=buckets then return {'ERROR','库存桶未就绪'} end
    local sum=0 local values={}
    for i=0,buckets-1 do
        local n=tonumber(redis.call('hget',KEYS[8],tostring(i)))
        if not n or n<0 or n~=math.floor(n) then return {'ERROR','库存桶未就绪'} end
        values[i]=n sum=sum+n
    end
    if sum~=stock then return {'ERROR','库存桶未就绪'} end
    -- Decimal modulo avoids converting a Java Long to a lossy Lua double.
    local preferred=0
    for digit in string.gmatch(ARGV[1],'%d') do preferred=(preferred*10+tonumber(digit))%buckets end
    bucket=-1
    for offset=0,buckets-1 do
        local candidate=(preferred+offset)%buckets
        if values[candidate]>0 then bucket=candidate break end
    end
    if bucket<0 then return {'ERROR','库存不足'} end
end
-- Keep command JSON opaque: Lua numbers would truncate large Java Long IDs.
local body=cjson.encode({command=ARGV[6],price=m[6],acceptedAt=tostring(now),bucketId=bucket,bucketCount=buckets})
local record=cjson.encode({orderNumber=ARGV[4],requestHash=ARGV[3],submitStatus='ACCEPTED',bucketId=bucket,bucketCount=buckets})
-- Pending command is written first: a later runtime error cannot leave an untraceable debit.
redis.call('hset',KEYS[5],ARGV[4],body)
redis.call('hset',KEYS[7],ARGV[4],tostring(now))
redis.call('hincrby',KEYS[1],'outstandingCount',1)
redis.call('decr',KEYS[2])
if buckets>1 then redis.call('hincrby',KEYS[8],tostring(bucket),-1) end
redis.call('hset',KEYS[3],ARGV[1],ARGV[4])
redis.call('hset',KEYS[4],field,record)
return {'OK',record}
