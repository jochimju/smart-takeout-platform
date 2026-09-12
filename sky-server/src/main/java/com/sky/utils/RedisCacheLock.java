package com.sky.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 菜单重建锁：续期、解锁和回填都必须校验持有者。 */
@Component
@Slf4j
public class RedisCacheLock {
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) end return 0", Long.class);
    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then redis.call('psetex',KEYS[2],ARGV[3],ARGV[2]); return 1 end return 0", Long.class);
    private final RedisTemplate redis;
    private final long leaseMillis;
    private final DefaultRedisScript<Long> renew;
    private final ScheduledThreadPoolExecutor scheduler;

    public RedisCacheLock(@org.springframework.beans.factory.annotation.Qualifier("redisTemplate") RedisTemplate redis,
                          @Value("${sky.cache.lock-lease-millis:10000}") long leaseMillis) {
        if (leaseMillis < 300) {
            throw new IllegalArgumentException("cache lock lease must be at least 300ms");
        }
        this.redis = redis;
        this.leaseMillis = leaseMillis;
        this.renew = new DefaultRedisScript<>(
                "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('pexpire',KEYS[1],"
                        + leaseMillis + ") end return 0", Long.class);
        this.scheduler = new ScheduledThreadPoolExecutor(2, runnable -> {
            Thread thread = new Thread(runnable, "menu-cache-lock-renewal");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
    }

    public Lease tryAcquire(String key) {
        String token = UUID.randomUUID().toString();
        // 使用同一 RedisTemplate 的序列化规则写锁和传 Lua 参数。
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, leaseMillis, TimeUnit.MILLISECONDS))) {
            return null;
        }
        Lease lease = new Lease(key, token);
        try {
            lease.renewal = scheduler.scheduleAtFixedRate(lease::renew, leaseMillis / 3,
                    leaseMillis / 3, TimeUnit.MILLISECONDS);
            return lease;
        } catch (RuntimeException ex) {
            lease.close();
            throw ex;
        }
    }

    @PreDestroy
    public void shutdown() { scheduler.shutdownNow(); }

    public final class Lease implements AutoCloseable {
        private final String key;
        private final String token;
        private final AtomicBoolean valid = new AtomicBoolean(true);
        private volatile ScheduledFuture<?> renewal;

        private Lease(String key, String token) {
            this.key = key;
            this.token = token;
        }

        private void renew() {
            if (!valid.get()) {
                cancelRenewal();
                return;
            }
            try {
                if (!Long.valueOf(1).equals(redis.execute(renew, Collections.singletonList(key), token))) {
                    valid.set(false);
                    cancelRenewal();
                }
            } catch (RuntimeException ex) {
                valid.set(false);
                cancelRenewal();
                log.warn("Cache lock renewal failed: {}", key, ex);
            }
        }

        public boolean publish(String cacheKey, Object value) {
            return publish(cacheKey, value, 1800000);
        }

        public boolean publish(String cacheKey, Object value, long ttlMillis) {
            return valid.get() && Long.valueOf(1).equals(
                    redis.execute(PUBLISH, org.springframework.data.redis.serializer.RedisSerializer.byteArray(), redis.getValueSerializer(), Arrays.asList(key, cacheKey), redis.getValueSerializer().serialize(token), redis.getValueSerializer().serialize(value), Long.toString(ttlMillis).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        private void cancelRenewal() {
            ScheduledFuture<?> task = renewal;
            if (task != null) {
                task.cancel(false);
            }
        }

        @Override
        public void close() {
            valid.set(false);
            cancelRenewal();
            // 即使续期失败仍安全尝试释放；异常不覆盖原始查询异常。
            try {
                redis.execute(UNLOCK, Collections.singletonList(key), token);
            } catch (RuntimeException ex) {
                log.warn("Cache lock release failed; waiting for expiry: {}", key, ex);
            }
        }
    }
}