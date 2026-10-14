-- ARGV: user, requestId (empty for legacy orders), orderNumber, status, error
-- Return -1 on missing cache: never invent inventory during recovery.
for i=1,#KEYS do
    local t=redis.call('type',KEYS[i]) t=type(t)=='table' and t.ok or t
    local expected=i==2 and 'string' or 'hash'
    if t~='none' and t~=expected then return -1 end
end
if ARGV[4]~='SUCCEEDED' and ARGV[4]~='FAILED' and ARGV[4]~='CANCELLED' then return -1 end
if redis.call('hget',KEYS[1],'ready')~='1' or redis.call('exists',KEYS[2])==0 then return -1 end
local outstandingType=redis.call('type',KEYS[7])
outstandingType=type(outstandingType)=='table' and outstandingType.ok or outstandingType
if outstandingType~='none' and outstandingType~='hash' then return -1 end
if redis.call('hget',KEYS[1],'admissionReady')=='1' and tonumber(redis.call('hget',KEYS[1],'outstandingCount'))~=redis.call('hlen',KEYS[7]) then return -1 end
local function releaseSlot()
    if redis.call('hdel',KEYS[7],ARGV[3])==1 then redis.call('hincrby',KEYS[1],'outstandingCount',-1) end
end
local field=ARGV[1]..':'..ARGV[2]
local raw=redis.call('hget',KEYS[4],field)
local record=raw and cjson.decode(raw) or nil
if record and record.orderNumber~=ARGV[3] then return -2 end
local buckets=tonumber(redis.call('hget',KEYS[1],'bucketCount') or '1')
local bucket=tonumber(ARGV[6] or '0')
if buckets~=1 and buckets~=2 and buckets~=4 and buckets~=8 then return -1 end
if buckets>1 then
    if not record or record.bucketCount~=buckets or record.bucketId~=bucket or
        not bucket or bucket<0 or bucket>=buckets or not KEYS[8] then return -1 end
    local sum=0
    for i=0,buckets-1 do
        local n=tonumber(redis.call('hget',KEYS[8],tostring(i)))
        if not n or n<0 or n~=math.floor(n) then return -1 end
        sum=sum+n
    end
    if redis.call('hlen',KEYS[8])~=buckets or sum~=tonumber(redis.call('get',KEYS[2])) then return -1 end
end
if ARGV[4]=='SUCCEEDED' then
    if record and record.submitStatus=='ACCEPTED' then
        record.submitStatus='SUCCEEDED'
        redis.call('hset',KEYS[4],field,cjson.encode(record))
    end
    releaseSlot()
    return 1
end
releaseSlot()
if redis.call('hexists',KEYS[6],ARGV[3])==1 then return 1 end
if redis.call('hget',KEYS[3],ARGV[1])==ARGV[3] then
    redis.call('incr',KEYS[2])
    if buckets>1 then redis.call('hincrby',KEYS[8],tostring(bucket),1) end
    redis.call('hdel',KEYS[3],ARGV[1])
end
redis.call('hset',KEYS[6],ARGV[3],ARGV[4])
if record then
    record.submitStatus=ARGV[4] record.failureMessage=ARGV[5]
    redis.call('hset',KEYS[4],field,cjson.encode(record))
end
return 1
