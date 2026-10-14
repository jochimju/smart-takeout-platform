package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.entity.SeckillActivity;
import com.sky.entity.SeckillReservation;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.SeckillOrderGuardMapper;
import com.sky.vo.OrderSubmitVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.ZoneId;
import java.util.*;

/** Redis owns outstanding predebits. Never overwrite it from DB while acceptance is enabled. */
@Component
public class SeckillCache {
    @org.springframework.beans.factory.annotation.Autowired private StringRedisTemplate redis;
    @org.springframework.beans.factory.annotation.Autowired private SeckillOrderGuardMapper guards;
    @Value("${sky.seckill.async-cache-prefix:seckill:v3:}") private String prefix;
    @Value("${sky.seckill.max-pending:10000}") private int maxPending;
    @Value("${sky.seckill.max-outstanding:500}") private int maxOutstanding=500;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private io.micrometer.core.instrument.MeterRegistry meters;
    private io.micrometer.core.instrument.Counter capacityRejected;
    @jakarta.annotation.PostConstruct void registerAdmissionMetrics() {
        if(meters!=null)capacityRejected=io.micrometer.core.instrument.Counter.builder("seckill.admission.rejected")
            .tag("reason","capacity").register(meters);
    }
    private static <T> DefaultRedisScript<T> script(String path,Class<T> type) {
        DefaultRedisScript<T> script=new DefaultRedisScript<>();
        var source=new org.springframework.scripting.support.ResourceScriptSource(new ClassPathResource(path));
        source.setEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        script.setScriptSource(source);script.setResultType(type);return script;
    }
    private static final DefaultRedisScript<List> ACCEPT=script("lua/seckill_async_accept.lua",List.class);
    private static final DefaultRedisScript<Long> FINISH=script("lua/seckill_async_finish.lua",Long.class);
    private static final DefaultRedisScript<Long> INIT = new DefaultRedisScript<>(
        "if redis.call('exists',KEYS[1])==1 then if redis.call('hget',KEYS[1],'prepared')=='1' then return 0 else return -1 end end " +
        "for i=2,8 do if redis.call('exists',KEYS[i])==1 then return -1 end end " +
        "redis.call('hmset',KEYS[1],'ready','0','admissionReady','1','outstandingCount','0','status',ARGV[1],'setmeal',ARGV[2],'begin',ARGV[3],'end',ARGV[4],'price',ARGV[5],'bucketCount',ARGV[7]) " +
        "redis.call('set',KEYS[2],ARGV[6]) " +
        "local c=tonumber(ARGV[7]) if c>1 then for b=0,c-1 do local n=math.floor(tonumber(ARGV[6])/c)+(b<tonumber(ARGV[6])%c and 1 or 0) redis.call('hset',KEYS[8],tostring(b),tostring(n)) end end " +
        "for i=8,#ARGV,2 do redis.call('hset',KEYS[3],ARGV[i],ARGV[i+1]) end redis.call('hset',KEYS[1],'prepared','1') return 1",Long.class);

    public List<String> keys(Long id) {
        String base=prefix+"{"+id+"}:";
        return Arrays.asList(base+"meta",base+"stock",base+"users",base+"requests",base+"commands",base+"released",base+"outstanding",base+"buckets");
    }
    /** Called under activity lock, before DB initialization marker is committed. Initially closed. */
    public void initialize(SeckillActivity a) {
        List<String> args=new ArrayList<>(Arrays.asList(a.getStatus().toString(),a.getSetmealId().toString(),
            String.valueOf(a.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),
            String.valueOf(a.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),
            a.getSeckillPrice().toPlainString(),a.getRemainingStock().toString(),String.valueOf(a.getBucketCount()==null?1:a.getBucketCount())));
        for(SeckillReservation owner:guards.users(a.getId())) { args.add(owner.getUserId().toString()); args.add(owner.getOrderNumber()); }
        Long result=redis.execute(INIT,keys(a.getId()),args.toArray());
        Object count=redis.opsForHash().get(keys(a.getId()).get(0),"bucketCount");
        if(!String.valueOf(a.getBucketCount()==null?1:a.getBucketCount()).equals(count==null?"1":count.toString()))
            throw new OrderBusinessException("库存桶配置与缓存不一致，已暂停受理");
        if(result==null || result<0) throw new OrderBusinessException("秒杀缓存有残留数据，需要人工核对，不能重置库存");
    }
    public void enable(Long id) {
        if(!"1".equals(redis.opsForHash().get(keys(id).get(0),"prepared")) || !"1".equals(redis.opsForHash().get(keys(id).get(0),"admissionReady")) || !Boolean.TRUE.equals(redis.hasKey(keys(id).get(1)))) throw new OrderBusinessException("秒杀库存或积压账本未就绪，不能自动恢复名额");
        redis.opsForHash().put(keys(id).get(0),"ready","1");
    }
    public boolean prepared(Long id) { return "1".equals(redis.opsForHash().get(keys(id).get(0),"prepared")); }
    public boolean ready(Long id) {
        return "1".equals(redis.opsForHash().get(keys(id).get(0),"ready")) && "1".equals(redis.opsForHash().get(keys(id).get(0),"admissionReady")) && Boolean.TRUE.equals(redis.hasKey(keys(id).get(1)));
    }
    public void pause(Long id) { redis.opsForHash().put(keys(id).get(0),"status","0"); }
    public boolean drained(Long id) {
        var k=keys(id);
        return ready(id) && redis.opsForHash().size(k.get(4))==0 && redis.opsForHash().size(k.get(6))==0
                && "0".equals(String.valueOf(redis.opsForHash().get(k.get(0),"outstandingCount")));
    }
    public Integer stock(Long id) {
        String value=redis.opsForValue().get(keys(id).get(1));
        if(value==null) throw new OrderBusinessException("秒杀缓存未就绪");
        return Integer.valueOf(value);
    }
    public OrderSubmitVO accept(SeckillOrderMessageDTO command) {
        List<?> result=redis.execute(ACCEPT,keys(command.getActivityId()),command.getUserId().toString(),
            command.getRequest().getRequestId(),command.getRequestHash(),command.getOrderNumber(),command.getSetmealId().toString(),
            JSON.toJSONString(command),String.valueOf(maxPending),String.valueOf(maxOutstanding));
        if(result==null || result.size()!=2) throw new OrderBusinessException("秒杀受理状态未知，请使用同一请求查询");
        if(!"OK".equals(result.get(0))) {
            if("BUSY".equals(result.get(0))) {
                if(capacityRejected!=null)capacityRejected.increment();
                throw new com.sky.exception.SeckillBusyException();
            }
            throw new OrderBusinessException(String.valueOf(result.get(1)));
        }
        return JSON.parseObject(String.valueOf(result.get(1)),OrderSubmitVO.class);
    }
    public OrderSubmitVO find(Long activity,Long user,String request) {
        Object raw=redis.opsForHash().get(keys(activity).get(3),user+":"+request);
        return raw==null?null:JSON.parseObject(raw.toString(),OrderSubmitVO.class);
    }
    /** One HMGET preserves per-request identity validation without N Redis round trips. */
    public Map<String,OrderSubmitVO> findBatch(Long activity,List<SeckillOrderMessageDTO> commands) {
        if(commands.isEmpty())return Map.of();
        if(commands.size()>200)throw new IllegalArgumentException("Seckill handoff validation exceeds 200");
        var fields=commands.stream().map(command->command.getUserId()+":"+command.getRequest().getRequestId()).toList();
        var values=redis.<String,String>opsForHash().multiGet(keys(activity).get(3),fields);
        var results=new LinkedHashMap<String,OrderSubmitVO>();
        for(int i=0;i<commands.size();i++) {
            if(values.get(i)==null)continue;
            var command=commands.get(i);
            try {
                var record=JSON.parseObject(values.get(i));
                if(command.getOrderNumber().equals(record.getString("orderNumber")) &&
                        command.getRequestHash()!=null && command.getRequestHash().equals(record.getString("requestHash")))
                    results.put(command.getOrderNumber(),record.toJavaObject(OrderSubmitVO.class));
            } catch(com.alibaba.fastjson.JSONException malformed) { /* Retain this task for reconciliation. */ }
        }
        return results;
    }
    /** Bounded scan; concurrent bridges may repeat work, DB uniqueness makes handoff idempotent. */
    public Map<String,String> pending(Long id) {
        Map<String,String> result=new LinkedHashMap<>();
        try(var cursor=redis.opsForHash().scan(keys(id).get(4),ScanOptions.scanOptions().count(200).build())) {
            while(cursor.hasNext() && result.size()<200) {
                var entry=cursor.next(); result.put(entry.getKey().toString(),entry.getValue().toString());
            }
        }
        return result;
    }
    public void handedOffBatch(Long id,java.util.List<String> numbers) {
        if(!numbers.isEmpty()) redis.opsForHash().delete(keys(id).get(4),numbers.toArray());
    }
    public void handedOff(Long id,String number) { redis.opsForHash().delete(keys(id).get(4),number); }
    public void finish(Long activity,Long user,String request,String number,String state,String error) {
        finish(activity,user,request,number,state,error,0);
    }
    public void finish(Long activity,Long user,String request,String number,String state,String error,int bucket) {
        Long result=redis.execute(FINISH,keys(activity),user.toString(),request==null?"":request,number,state,error==null?"":error,String.valueOf(bucket));
        if(!Long.valueOf(1).equals(result)) throw new IllegalStateException("Redis compensation requires reconciliation: "+number);
    }
    private volatile String finishSha;
    /** Pipelining changes round trips, not Lua atomicity; partial successes can safely replay. */
    public List<Boolean> finishBatch(List<Map<String,Object>> events) {
        if(events.isEmpty())return List.of();
        try {
            if(finishSha==null)finishSha=redis.execute((org.springframework.data.redis.core.RedisCallback<String>)
                connection->connection.scriptingCommands().scriptLoad(FINISH.getScriptAsString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            byte[] sha=finishSha.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var results=redis.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection->{
                for(var event:events) {
                    var args=new ArrayList<String>(keys(((Number)event.get("activity_id")).longValue()));
                    Collections.addAll(args,event.get("user_id").toString(),event.get("request_id")==null?"":event.get("request_id").toString(),
                        event.get("order_number").toString(),event.get("state").toString(),event.get("last_error")==null?"":event.get("last_error").toString(),
                        event.get("bucket_id")==null?"0":event.get("bucket_id").toString());
                    byte[][] encoded=args.stream().map(value->value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toArray(byte[][]::new);
                    connection.scriptingCommands().evalSha(sha,org.springframework.data.redis.connection.ReturnType.INTEGER,8,encoded);
                }
                return null;
            });
            if(results.size()!=events.size())throw new IllegalStateException("Incomplete Redis pipeline replies");
            return results.stream().map(value->Long.valueOf(1).equals(value)).toList();
        } catch(RuntimeException failure) {
            // A server restart/eviction can remove scripts. Reload next round, retaining every event.
            finishSha=null;throw failure;
        }
    }
    public void evict(Long id) { redis.delete(keys(id)); }
}
