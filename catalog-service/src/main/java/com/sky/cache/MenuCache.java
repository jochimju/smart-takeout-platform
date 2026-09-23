package com.sky.cache;

import com.sky.constant.RedisKeyConstant;
import com.sky.exception.BaseException;
import com.sky.result.Result;
import com.sky.utils.RedisCacheLock;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class MenuCache {
    private static final String DISH_CACHE_VERSION_KEY = "dish:cache:version";
    private final RedisTemplate redis;
    private final RedisCacheLock lock;
    private final MenuCacheMetrics metrics;
    public MenuCache(@org.springframework.beans.factory.annotation.Qualifier("redisTemplate") RedisTemplate redis, RedisCacheLock lock, MenuCacheMetrics metrics) {
        this.redis = redis; this.lock = lock; this.metrics = metrics;
    }
    public <T> List<T> get(String kind, Long categoryId, Supplier<List<T>> loader) {
        return query(kind, categoryId, loader, false);
    }
    public <T> List<T> warm(String kind, Long categoryId, Supplier<List<T>> loader) {
        return query(kind, categoryId, loader, true);
    }
    /** O(1) logical invalidation: old, versioned entries expire naturally. */
    public void invalidateDishes() {
        cacheVersion(); // Migrate legacy Java-serialized values before Redis INCR.
        redis.execute((RedisCallback<Long>) connection ->
                connection.stringCommands().incr(DISH_CACHE_VERSION_KEY.getBytes(StandardCharsets.UTF_8)));
    }
    public void invalidateAll() {
        invalidateDishes();
        java.util.Set<String> keys = redis.keys("userSetmealCache::*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
    private <T> List<T> query(String kind, Long categoryId, Supplier<List<T>> loader, boolean warm) {
        if (categoryId == null || !("dish".equals(kind) || "setmeal".equals(kind))) {
            throw new BaseException("请选择有效的菜单分类");
        }
        String key = "dish".equals(kind) ? dishKey(categoryId) : "userSetmealCache::" + categoryId;
        metrics.increment(kind, warm ? "warmups" : "requests");
        boolean first = true;
        boolean contended = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new BaseException("菜单查询已中断，请稍后重试");
                List<T> cached = read(kind, key);
                if (first && !warm) metrics.increment(kind, cached == null ? "misses" : "hits");
                first = false;
                if (cached != null) return cached; // 空列表也是有效缓存。
                RedisCacheLock.Lease lease = lock.tryAcquire(RedisKeyConstant.CACHE_LOCK + key);
                if (lease != null) {
                    try (RedisCacheLock.Lease ignored = lease) {
                        cached = read(kind, key);
                        if (cached != null) return cached;
                        metrics.increment(kind, warm ? "warmupLoads" : "loads");
                        List<T> loaded = Objects.requireNonNull(loader.get(), "menu loader returned null");
                        long ttl = loaded.isEmpty() ? ThreadLocalRandom.current().nextLong(60, 91)
                                : ThreadLocalRandom.current().nextLong(1800, 2101);
                        Object payload = "dish".equals(kind) ? loaded : Result.success(loaded);
                        if (!lease.publish(key, payload, ttl * 1000)) throw new BaseException("菜单数据正在刷新，请稍后重试");
                        return loaded;
                    }
                }
                if (!contended && !warm) metrics.increment(kind, "contentions");
                contended = true;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    if (!warm) metrics.increment(kind, "timeouts");
                    throw new BaseException("菜单数据正在刷新，请稍后重试");
                }
                try { TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(50), remaining)); }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new BaseException("菜单查询已中断，请稍后重试");
                }
            }
        } catch (RuntimeException ex) {
            metrics.increment(kind, warm ? "warmupErrors" : "errors");
            throw ex;
        }
    }
    private String dishKey(Long categoryId) {
        return "dish:v" + cacheVersion() + ":" + categoryId;
    }
    /**
     * Cache payloads use Java serialization, but this counter must be a Redis-native integer for INCR.
     * A non-numeric legacy value is safe to reset because it only versions disposable cache entries.
     */
    private long cacheVersion() {
        byte[] key = DISH_CACHE_VERSION_KEY.getBytes(StandardCharsets.UTF_8);
        byte[] value = (byte[]) redis.execute((RedisCallback<byte[]>) connection -> connection.stringCommands().get(key));
        if (value == null) {
            redis.execute((RedisCallback<Boolean>) connection -> connection.stringCommands().setNX(key, "1".getBytes(StandardCharsets.UTF_8)));
            value = (byte[]) redis.execute((RedisCallback<byte[]>) connection -> connection.stringCommands().get(key));
        }
        try {
            return Long.parseLong(new String(value, StandardCharsets.UTF_8));
        } catch (NumberFormatException ex) {
            redis.execute((RedisCallback<Void>) connection -> {
                connection.stringCommands().set(key, "1".getBytes(StandardCharsets.UTF_8));
                return null;
            });
            return 1L;
        }
    }
    @SuppressWarnings("unchecked")
    private <T> List<T> read(String kind, String key) {
        Object value = redis.opsForValue().get(key);
        if (value == null) return null;
        return "dish".equals(kind) ? (List<T>) value : ((Result<List<T>>) value).getData();
    }
}
