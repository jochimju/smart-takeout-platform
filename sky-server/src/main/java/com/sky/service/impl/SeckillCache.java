package com.sky.service.impl;

import com.sky.entity.SeckillActivity;
import com.sky.entity.SeckillReservation;
import com.sky.mapper.SeckillOrderGuardMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.ZoneId;
import java.util.*;

/** Rebuild and reserve must run while the caller holds the database activity row lock. */
@Component
public class SeckillCache {
    @Autowired private StringRedisTemplate redis;
    @Autowired private SeckillOrderGuardMapper guards;
    @Value("${sky.seckill.cache-prefix:seckill:v2:}") private String prefix;
    private static final DefaultRedisScript<Long> REBUILD = new DefaultRedisScript<>(
        "local v=redis.call('hget',KEYS[1],'version') " +
        "if v and tonumber(v)>tonumber(ARGV[1]) then return -1 end " +
        "redis.call('hmset',KEYS[1],'version',ARGV[1],'status',ARGV[2],'setmeal',ARGV[3],'begin',ARGV[4],'end',ARGV[5]) " +
        "redis.call('set',KEYS[2],ARGV[6]) redis.call('del',KEYS[3]) " +
        "for i=7,#ARGV,2 do redis.call('hset',KEYS[3],ARGV[i],ARGV[i+1]) end " +
        "for i=1,3 do redis.call('pexpire',KEYS[i],600000) end return 1", Long.class);
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>(
        "redis.replicate_commands() " +
        "if redis.call('hget',KEYS[1],'version')~=ARGV[1] then return -6 end " +
        "if redis.call('hget',KEYS[1],'status')~='1' then return -2 end " +
        "if redis.call('hget',KEYS[1],'setmeal')~=ARGV[2] then return -3 end " +
        "local t=redis.call('time') local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000) " +
        "if now<tonumber(redis.call('hget',KEYS[1],'begin')) then return -4 end " +
        "if now>tonumber(redis.call('hget',KEYS[1],'end')) then return -5 end " +
        "if redis.call('hexists',KEYS[3],ARGV[3])==1 then return -1 end " +
        "local stock=tonumber(redis.call('get',KEYS[2])) if not stock or stock<=0 then return 0 end " +
        "redis.call('decr',KEYS[2]) redis.call('hset',KEYS[3],ARGV[3],ARGV[4]) return 1", Long.class);

    public List<String> keys(Long id) {
        String base=prefix+"{"+id+"}:";
        return Arrays.asList(base+"meta",base+"stock",base+"users");
    }
    public void rebuild(SeckillActivity a) {
        List<String> args=new ArrayList<>(Arrays.asList(a.getStockVersion().toString(),a.getStatus().toString(),
            a.getSetmealId().toString(),String.valueOf(a.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),
            String.valueOf(a.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),a.getRemainingStock().toString()));
        for(SeckillReservation user:guards.users(a.getId())) {
            args.add(user.getUserId().toString()); args.add(user.getOrderNumber());
        }
        if(!Long.valueOf(1).equals(redis.execute(REBUILD,keys(a.getId()),args.toArray())))
            throw new IllegalStateException("秒杀缓存版本不一致，请稍后重试");
    }
    public Long reserve(SeckillActivity a,Long user,String request) {
        return redis.execute(RESERVE,keys(a.getId()),a.getStockVersion().toString(),a.getSetmealId().toString(),user.toString(),request);
    }

    /** Removes all Redis state for a deleted activity. */
    public void evict(Long activityId) {
        redis.delete(keys(activityId));
    }
}
