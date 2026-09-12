package com.sky.service.impl;

import com.sky.config.RedisConfiguration;
import com.sky.constant.RedisKeyConstant;
import com.sky.entity.Dish;
import com.sky.exception.BaseException;
import com.sky.utils.RedisCacheLock;
import com.sky.vo.DishVO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 在独立 Redis 上验证真实 Lua、JDK 序列化、租期和并发重建。 */
@EnabledIfSystemProperty(named = "step2.redis.port", matches = "\\d+")
class DishCacheMutexTest {
    private static LettuceConnectionFactory factory;
    private RedisTemplate redis;
    private RedisCacheLock lock;
    private DishServiceImpl service;
    private long categoryId;
    private String key;
    private String lockKey;
    private final List<DishVO> menu = Collections.singletonList(new DishVO());

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("step2.redis.port")));
        factory.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() { factory.destroy(); }

    @BeforeEach
    void setup() {
        redis = new RedisConfiguration().redisTemplate(factory);
        redis.afterPropertiesSet();
        lock = new RedisCacheLock(redis, 600);
        service = spy(new DishServiceImpl());
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "menuCache", new com.sky.cache.MenuCache(redis, lock, new com.sky.cache.MenuCacheMetrics()));
        categoryId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        key = "dish_" + categoryId;
        lockKey = RedisKeyConstant.CACHE_LOCK + key;
        doReturn(menu).when(service).listWithFlavor(any(Dish.class));
    }

    @AfterEach
    void cleanup() {
        Thread.interrupted();
        lock.shutdown();
        redis.delete(Arrays.asList(key, lockKey));
    }

    @Test
    void cacheHitDoesNotQueryDatabase() {
        redis.opsForValue().set(key, menu);
        assertEquals(1, service.listWithFlavorCache(categoryId).size());
        verify(service, never()).listWithFlavor(any(Dish.class));
        assertFalse(redis.hasKey(lockKey));
    }

    @Test
    void concurrentMissesOnlyRebuildOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        doAnswer(call -> { loads.incrementAndGet(); Thread.sleep(250); return menu; })
                .when(service).listWithFlavor(any(Dish.class));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<DishVO>>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> { start.await(); return service.listWithFlavorCache(categoryId); }));
            }
            start.countDown();
            for (Future<List<DishVO>> result : results) {
                assertEquals(1, result.get(4, TimeUnit.SECONDS).size());
            }
            assertEquals(1, loads.get());
            assertFalse(redis.hasKey(lockKey));
            assertTrue(redis.getExpire(key, TimeUnit.SECONDS) > 1700);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void lockContentionTimesOutWithoutDatabaseFallback() {
        try (RedisCacheLock.Lease owner = lock.tryAcquire(lockKey)) {
            assertNotNull(owner);
            long start = System.nanoTime();
            assertThrows(BaseException.class, () -> service.listWithFlavorCache(categoryId));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2500);
            verify(service, never()).listWithFlavor(any(Dish.class));
            assertTrue(redis.hasKey(lockKey));
        }
    }

    @Test
    void slowRebuildIsRenewedBeyondOriginalLease() {
        doAnswer(call -> {
            Object owner = redis.opsForValue().get(lockKey);
            Thread.sleep(1400);
            assertEquals(owner, redis.opsForValue().get(lockKey));
            assertNull(lock.tryAcquire(lockKey));
            return menu;
        }).when(service).listWithFlavor(any(Dish.class));
        assertEquals(1, service.listWithFlavorCache(categoryId).size());
        assertFalse(redis.hasKey(lockKey));
        assertNotNull(redis.opsForValue().get(key));
    }

    @Test
    void expiredOwnerCannotPublishOrDeleteNewOwnersLock() throws Exception {
        RedisCacheLock.Lease old = lock.tryAcquire(lockKey);
        assertNotNull(old);
        lock.shutdown(); // 模拟旧进程暂停，无法续期。
        Thread.sleep(900);
        redis.opsForValue().set(lockKey, "new-owner", 5, TimeUnit.SECONDS);
        assertFalse(old.publish(key, menu));
        old.close();
        assertEquals("new-owner", redis.opsForValue().get(lockKey));
        assertNull(redis.opsForValue().get(key));
    }

    @Test
    void lostOwnershipDuringQueryRejectsStaleResult() {
        doAnswer(call -> {
            redis.opsForValue().set(lockKey, "new-owner", 5, TimeUnit.SECONDS);
            return menu;
        }).when(service).listWithFlavor(any(Dish.class));
        assertThrows(BaseException.class, () -> service.listWithFlavorCache(categoryId));
        assertNull(redis.opsForValue().get(key));
        assertEquals("new-owner", redis.opsForValue().get(lockKey));
    }

    @Test
    void databaseExceptionReleasesLock() {
        doThrow(new IllegalStateException("database unavailable")).when(service).listWithFlavor(any(Dish.class));
        assertThrows(IllegalStateException.class, () -> service.listWithFlavorCache(categoryId));
        assertFalse(redis.hasKey(lockKey));
        assertNull(redis.opsForValue().get(key));
    }

    @Test
    void interruptionDoesNotQueryDatabase() {
        Thread.currentThread().interrupt();
        assertThrows(BaseException.class, () -> service.listWithFlavorCache(categoryId));
        assertTrue(Thread.currentThread().isInterrupted());
        verify(service, never()).listWithFlavor(any(Dish.class));
    }

    @Test
    void secondCacheCheckAvoidsRedundantQuery() {
        RedisCacheLock spyLock = spy(lock);
        doAnswer(call -> {
            redis.opsForValue().set(key, menu);
            return lock.tryAcquire(lockKey);
        }).when(spyLock).tryAcquire(lockKey);
        ReflectionTestUtils.setField(service, "menuCache", new com.sky.cache.MenuCache(redis, spyLock, new com.sky.cache.MenuCacheMetrics()));
        assertEquals(1, service.listWithFlavorCache(categoryId).size());
        verify(service, never()).listWithFlavor(any(Dish.class));
        assertFalse(redis.hasKey(lockKey));
    }
    @Test
    void emptyDishResultsAreCachedWithShortTtl() {
        doReturn(Collections.emptyList()).when(service).listWithFlavor(any(Dish.class));
        assertTrue(service.listWithFlavorCache(categoryId).isEmpty());
        assertTrue(service.listWithFlavorCache(categoryId).isEmpty());
        verify(service, times(1)).listWithFlavor(any(Dish.class));
        long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertTrue(ttl >= 59 && ttl <= 90);
    }

    @Test
    void setmealConcurrentReadsUseSameMutexAndResultPayload() throws Exception {
        SetmealServiceImpl setmeals = spy(new SetmealServiceImpl());
        com.sky.cache.MenuCacheMetrics metrics = new com.sky.cache.MenuCacheMetrics();
        ReflectionTestUtils.setField(setmeals, "menuCache", new com.sky.cache.MenuCache(redis, lock, metrics));
        AtomicInteger loads = new AtomicInteger();
        doAnswer(call -> {
            loads.incrementAndGet(); Thread.sleep(200);
            return Collections.singletonList(new com.sky.entity.Setmeal());
        }).when(setmeals).list(any(com.sky.entity.Setmeal.class));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        String mealKey = "userSetmealCache::" + categoryId;
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> {
                start.await(); assertEquals(1, setmeals.listCache(categoryId).size()); return null;
            }));
            start.countDown();
            for (Future<?> f : futures) f.get(4, TimeUnit.SECONDS);
            assertEquals(1, loads.get());
            assertTrue(redis.opsForValue().get(mealKey) instanceof com.sky.result.Result);
            assertTrue(redis.getExpire(mealKey, TimeUnit.SECONDS) >= 1799);
            assertEquals(1L, metrics.snapshot().get("setmeal").get("loads"));
        } finally {
            pool.shutdownNow(); pool.awaitTermination(3, TimeUnit.SECONDS);
            redis.delete(Arrays.asList(mealKey, RedisKeyConstant.CACHE_LOCK + mealKey));
        }
    }

    @Test
    void warmupUsesExactQueryKeysAndDoesNotInflateRequestHits() {
        com.sky.cache.MenuCacheMetrics metrics = new com.sky.cache.MenuCacheMetrics();
        com.sky.cache.MenuCache cache = new com.sky.cache.MenuCache(redis, lock, metrics);
        com.sky.mapper.CategoryMapper categories = mock(com.sky.mapper.CategoryMapper.class);
        com.sky.service.SetmealService setmeals = mock(com.sky.service.SetmealService.class);
        com.sky.entity.Category dishCategory = new com.sky.entity.Category();
        dishCategory.setId(categoryId); dishCategory.setType(1);
        com.sky.entity.Category mealCategory = new com.sky.entity.Category();
        mealCategory.setId(categoryId); mealCategory.setType(2);
        when(categories.list(null)).thenReturn(Arrays.asList(dishCategory, mealCategory));
        when(setmeals.list(any())).thenReturn(Collections.emptyList());
        com.sky.cache.MenuCacheWarmup warmup = new com.sky.cache.MenuCacheWarmup(categories, service, setmeals, cache, true);
        String mealKey = "userSetmealCache::" + categoryId;
        try {
            warmup.warmup(); warmup.warmup(); // 第二实例/重复预热复用已有缓存。
            assertEquals(1, cache.get("dish", categoryId, () -> { throw new AssertionError("unexpected load"); }).size());
            assertTrue(cache.get("setmeal", categoryId, () -> { throw new AssertionError("unexpected load"); }).isEmpty());
            verify(service, times(1)).listWithFlavor(any());
            verify(setmeals, times(1)).list(any());
            assertEquals(1L, metrics.snapshot().get("dish").get("hits"));
            assertEquals(1L, metrics.snapshot().get("dish").get("requests"));
            assertEquals(1L, metrics.snapshot().get("setmeal").get("warmupLoads"));
            assertTrue(redis.getExpire(mealKey, TimeUnit.SECONDS) <= 90);
        } finally { redis.delete(mealKey); }
    }

    @Test
    void springCacheEvictionStillClearsSetmealPayload() {
        String mealKey = "userSetmealCache::" + categoryId;
        com.sky.cache.MenuCache cache = new com.sky.cache.MenuCache(redis, lock, new com.sky.cache.MenuCacheMetrics());
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext context =
                     new org.springframework.context.annotation.AnnotationConfigApplicationContext(EvictionConfig.class)) {
            cache.get("setmeal", categoryId, Collections::emptyList);
            com.sky.dto.SetmealDTO dto = new com.sky.dto.SetmealDTO();
            dto.setCategoryId(categoryId);
            com.sky.controller.admin.SetmealController controller = context.getBean(com.sky.controller.admin.SetmealController.class);
            com.sky.service.SetmealService mockService = context.getBean(com.sky.service.SetmealService.class);
            doThrow(new IllegalStateException("rollback")).when(mockService).update(any());
            assertThrows(IllegalStateException.class, () -> controller.update(dto));
            assertNotNull(redis.opsForValue().get(mealKey));
            controller.save(dto);
            assertNull(redis.opsForValue().get(mealKey));
            cache.get("setmeal", categoryId, Collections::emptyList);
            doNothing().when(mockService).update(any());
            controller.update(dto);
            assertNull(redis.opsForValue().get(mealKey));
        } finally { redis.delete(mealKey); }
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.cache.annotation.EnableCaching
    static class EvictionConfig {
        @org.springframework.context.annotation.Bean
        org.springframework.cache.CacheManager cacheManager() {
            return org.springframework.data.redis.cache.RedisCacheManager.create(factory);
        }
        @org.springframework.context.annotation.Bean
        com.sky.service.SetmealService setmealService() { return mock(com.sky.service.SetmealService.class); }
        @org.springframework.context.annotation.Bean
        com.sky.controller.admin.SetmealController controller() { return new com.sky.controller.admin.SetmealController(); }
    }

    @Test
    void coldVersusWarmReport() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        for (boolean warm : new boolean[]{false, true}) {
            com.sky.cache.MenuCacheMetrics metrics = new com.sky.cache.MenuCacheMetrics();
            com.sky.cache.MenuCache cache = new com.sky.cache.MenuCache(redis, lock, metrics);
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 10; i++) keys.add("dish_" + (categoryId + i));
            java.util.function.Supplier<List<DishVO>> loader = () -> {
                try { Thread.sleep(5); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                return menu;
            };
            try {
                redis.delete(keys);
                if (warm) for (int i = 0; i < 10; i++) cache.warm("dish", categoryId + i, loader);
                List<Long> times = new ArrayList<>();
                long started = System.nanoTime();
                for (int i = 0; i < 100; i++) {
                    long before = System.nanoTime();
                    cache.get("dish", categoryId + i % 10, loader);
                    times.add(System.nanoTime() - before);
                }
                Map<String, Object> sample = new LinkedHashMap<>(metrics.snapshot().get("dish"));
                sample.put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0);
                Collections.sort(times);
                sample.put("p95Ms", times.get(94) / 1_000_000.0);
                report.put(warm ? "warm" : "cold", sample);
                assertEquals(warm ? 100L : 90L, sample.get("hits"));
                assertEquals(warm ? 0L : 10L, sample.get("loads"));
            } finally { redis.delete(keys); }
        }
        java.nio.file.Files.write(java.nio.file.Paths.get("target/menu-cache-comparison.json"),
                com.alibaba.fastjson.JSON.toJSONString(report, true).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test
    void springWiringSelectsObjectRedisTemplate() {
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext context =
                     new org.springframework.context.annotation.AnnotationConfigApplicationContext(WiringConfig.class)) {
            assertNotNull(context.getBean(com.sky.cache.MenuCache.class));
            assertSame(context.getBean("redisTemplate"),
                    ReflectionTestUtils.getField(context.getBean(com.sky.cache.MenuCache.class), "redis"));
        }
    }
    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.Import({com.sky.cache.MenuCache.class, com.sky.cache.MenuCacheMetrics.class, RedisCacheLock.class})
    static class WiringConfig {
        @org.springframework.context.annotation.Bean
        RedisTemplate redisTemplate() {
            RedisTemplate template = new RedisConfiguration().redisTemplate(factory);
            return template;
        }
        @org.springframework.context.annotation.Bean
        org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate() {
            return new org.springframework.data.redis.core.StringRedisTemplate(factory);
        }
    }

    @Test
    void warmupFailureDoesNotPreventOtherCategories() {
        com.sky.mapper.CategoryMapper categories = mock(com.sky.mapper.CategoryMapper.class);
        com.sky.service.SetmealService meals = mock(com.sky.service.SetmealService.class);
        com.sky.cache.MenuCacheMetrics metrics = new com.sky.cache.MenuCacheMetrics();
        com.sky.cache.MenuCache cache = new com.sky.cache.MenuCache(redis, lock, metrics);
        com.sky.entity.Category a = new com.sky.entity.Category(); a.setId(categoryId); a.setType(1);
        com.sky.entity.Category b = new com.sky.entity.Category(); b.setId(categoryId); b.setType(2);
        when(categories.list(null)).thenReturn(Arrays.asList(a,b));
        doThrow(new IllegalStateException("failed dish query")).when(service).listWithFlavor(any());
        when(meals.list(any())).thenReturn(Collections.emptyList());
        try {
            assertDoesNotThrow(() -> new com.sky.cache.MenuCacheWarmup(categories, service, meals, cache, true).warmup());
            assertEquals(1L, metrics.snapshot().get("dish").get("warmupErrors"));
            assertNotNull(redis.opsForValue().get("userSetmealCache::" + categoryId));
        } finally { redis.delete("userSetmealCache::" + categoryId); }
    }
}
