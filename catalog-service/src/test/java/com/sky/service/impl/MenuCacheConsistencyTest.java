package com.sky.service.impl;

import com.sky.cache.MenuCache;
import com.sky.cache.MenuCacheMetrics;
import com.sky.config.RedisConfiguration;
import com.sky.dto.SetmealDTO;
import com.sky.utils.RedisCacheLock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Characterization tests: passing assertions also document reproduced consistency gaps.
 * Redis and Lua are real; database snapshots and write completion are controlled in memory.
 * Run only against an isolated Redis, because dish version and eviction are global.
 */
@EnabledIfSystemProperty(named = "step2.redis.port", matches = "\\d+")
class MenuCacheConsistencyTest {
    private LettuceConnectionFactory factory;
    private RedisTemplate redis;
    private RedisCacheLock lock;
    private MenuCache cache;
    private long category;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @BeforeEach void setup() {
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("step2.redis.port")));
        factory.afterPropertiesSet();
        redis = new RedisConfiguration().redisTemplate(factory);
        redis.afterPropertiesSet();
        lock = new RedisCacheLock(redis, 10000);
        cache = new MenuCache(redis, lock, new MenuCacheMetrics());
        category = Math.abs(UUID.randomUUID().getMostSignificantBits());
    }

    @AfterEach void close() throws Exception {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        lock.shutdown();
        // This server is dedicated to this test run. Clean only this category's entries.
        java.util.Set<String> keys = redis.keys("*" + category);
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        factory.destroy();
    }

    @Test void dishOldInflightReadCannotPolluteNewVersion() throws Exception {
        race("dish", cache::invalidateDishes, false);
    }

    @Test void setmealOldInflightReadCannotPolluteNewVersionAfterEvictions() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(EvictionConfig.class);
            context.registerBean(org.springframework.cache.CacheManager.class,
                    () -> org.springframework.data.redis.cache.RedisCacheManager.create(factory));
            context.registerBean(com.sky.service.SetmealService.class, () -> mock(com.sky.service.SetmealService.class));
            context.registerBean(com.sky.controller.admin.SetmealController.class,
                    com.sky.controller.admin.SetmealController::new);
            context.refresh();
            SetmealDTO dto = new SetmealDTO(); dto.setCategoryId(category);
            race("setmeal", () -> {
                cache.invalidateAll(); // Simulate successful committed invalidation delivery.
                context.getBean(com.sky.controller.admin.SetmealController.class).update(dto);
                // The controller no longer performs Redis invalidation; durable tasks own delivery.
            }, false);
        }
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.cache.annotation.EnableCaching
    static class EvictionConfig { }

    @org.springframework.context.annotation.Configuration
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class TransactionConfig { }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"save", "update", "delete", "status"})
    void everySetmealWriteInvalidatesOnlyAfterCommit(String operation) {
        verifyWriteTransaction(operation, false);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"save", "update", "delete", "status"})
    void rollbackOfEverySetmealWritePreservesVersionAndCache(String operation) {
        verifyWriteTransaction(operation, true);
    }

    private void verifyWriteTransaction(String operation, boolean rollback) {
        com.sky.mapper.SetmealMapper meals = mock(com.sky.mapper.SetmealMapper.class);
        com.sky.entity.Setmeal disabled = new com.sky.entity.Setmeal();
        disabled.setId(1L); disabled.setStatus(0);
        when(meals.getById(1L)).thenReturn(disabled);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfig.class);
            org.springframework.jdbc.core.JdbcTemplate jdbc = CacheOutboxDatabase.create();
            org.springframework.transaction.PlatformTransactionManager manager =
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
            context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> manager);
            context.registerBean(com.sky.cache.MenuCacheInvalidationOutbox.class,
                    () -> new com.sky.cache.MenuCacheInvalidationOutbox(jdbc, cache, manager));
            context.registerBean(com.sky.service.SetmealService.class, () -> new SetmealServiceImpl(meals,
                    mock(com.sky.mapper.SetmealDishMapper.class), mock(com.sky.mapper.DishMapper.class), cache));
            context.refresh();
            com.sky.service.SetmealService service = context.getBean(com.sky.service.SetmealService.class);
            cache.get("setmeal", category, () -> List.of("old"));
            String before = ReflectionTestUtils.invokeMethod(cache, "setmealKey", category);
            SetmealDTO dto = new SetmealDTO(); dto.setId(1L); dto.setCategoryId(category);
            org.springframework.transaction.support.TransactionTemplate tx =
                    new org.springframework.transaction.support.TransactionTemplate(
                            context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            tx.execute(status -> {
                switch (operation) {
                    case "save" -> service.saveWithDish(dto);
                    case "update" -> service.update(dto);
                    case "delete" -> service.deleteBatch(List.of(1L));
                    case "status" -> service.startOrStop(0, 1L);
                    default -> throw new AssertionError(operation);
                }
                assertEquals(before, ReflectionTestUtils.invokeMethod(cache, "setmealKey", category));
                assertEquals(List.of("old"), cache.get("setmeal", category, () -> List.of("new")));
                if (rollback) status.setRollbackOnly();
                return null;
            });
            String after = ReflectionTestUtils.invokeMethod(cache, "setmealKey", category);
            if (rollback) assertEquals(before, after); else assertNotEquals(before, after);
            assertEquals(List.of(rollback ? "old" : "new"),
                    cache.get("setmeal", category, () -> List.of("new")));
        }
    }

    @Test void legacyUnversionedSetmealCacheIsNeverRead() {
        String legacyKey = "userSetmealCache::" + category;
        redis.opsForValue().set(legacyKey, com.sky.result.Result.success(List.of("old")));
        assertEquals(List.of("new"), cache.get("setmeal", category, () -> List.of("new")));
    }

    private void race(String kind, Runnable invalidate, boolean expectStale) throws Exception {
        AtomicReference<List<String>> database = new AtomicReference<>(List.of("old"));
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch allowPublish = new CountDownLatch(1);
        Future<List<String>> oldRead = pool.submit(() -> cache.get(kind, category, () -> {
            List<String> snapshot = database.get();
            snapshotRead.countDown();
            try {
                if (!allowPublish.await(5, TimeUnit.SECONDS)) throw new AssertionError("publish not released");
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            return snapshot;
        }));
        try {
            assertTrue(snapshotRead.await(5, TimeUnit.SECONDS));
            database.set(List.of("new")); // Controlled database commit boundary.
            invalidate.run();
            allowPublish.countDown();
            assertEquals(List.of("old"), oldRead.get(5, TimeUnit.SECONDS));
            AtomicInteger reloads = new AtomicInteger();
            for (int i = 0; i < 20; i++) {
                assertEquals(List.of(expectStale ? "old" : "new"), cache.get(kind, category, () -> {
                    reloads.incrementAndGet(); return database.get();
                }));
            }
            assertEquals(expectStale ? 0 : 1, reloads.get());
            String key = "dish".equals(kind)
                    ? ReflectionTestUtils.invokeMethod(cache, "dishKey", category)
                    : ReflectionTestUtils.invokeMethod(cache, "setmealKey", category);
            long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            assertTrue(ttl >= 1790 && ttl <= 2100, "remaining TTL=" + ttl);
            System.out.println("CONSISTENCY " + kind + " stale=" + expectStale + " subsequentReads=20 reloads=" + reloads + " remainingTtlSeconds=" + ttl);
        } finally { allowPublish.countDown(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"dish", "setmeal"})
    void failedInvalidationAutomaticallyRecoversThroughDurableOutbox(String kind) {
        AtomicReference<List<String>> database = new AtomicReference<>(List.of("old"));
        assertEquals(List.of("old"), cache.get(kind, category, database::get));
        database.set(List.of("new"));
        MenuCache faulty = spy(cache);
        if ("dish".equals(kind)) doThrow(new RedisConnectionFailureException("injected outage")).when(faulty).invalidateDishes();
        else doThrow(new RedisConnectionFailureException("injected outage")).when(faulty).invalidateSetmeals();
        org.springframework.jdbc.core.JdbcTemplate jdbc = CacheOutboxDatabase.create();
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        var outbox = new com.sky.cache.MenuCacheInvalidationOutbox(jdbc, faulty, manager);
        new org.springframework.transaction.support.TransactionTemplate(manager)
                .executeWithoutResult(tx -> outbox.record(kind));
        for (int i = 0; i < 20; i++) assertEquals(List.of("old"), cache.get(kind, category, database::get));
        jdbc.update("update menu_cache_invalidation set next_retry_time=TIMESTAMP '2000-01-01 00:00:00'");
        new com.sky.cache.MenuCacheInvalidationOutbox(jdbc, cache, manager).retryPending();
        assertEquals(List.of("new"), cache.get(kind, category, database::get));
        assertEquals(1, jdbc.queryForObject("select count(*) from menu_cache_invalidation where status=1", Integer.class));
        System.out.println("CONSISTENCY " + kind + " invalidationFailure automaticDurableRetryRestores=true");
    }
}
