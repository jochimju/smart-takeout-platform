package com.sky.service.impl;

import com.sky.constant.StatusConstant;
import com.sky.entity.Setmeal;
import com.sky.listener.DishStatusCacheListener;
import com.sky.mapper.*;
import com.sky.service.DishService;
import org.junit.jupiter.api.*;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DishStatusCacheTest {
    private AnnotationConfigApplicationContext context;
    private DishService service;
    private RedisTemplate redis;
    private com.sky.cache.MenuCache menuCache;
    private CacheManager caches;
    private SetmealMapper setmeals;
    private TransactionTemplate transaction;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(DishService.class);
        redis = context.getBean(RedisTemplate.class);
        menuCache = context.getBean(com.sky.cache.MenuCache.class);
        clearInvocations(redis); // 排除 Spring 初始化 Bean 的生命周期调用。
        caches = context.getBean(CacheManager.class);
        setmeals = context.getBean(SetmealMapper.class);
        transaction = new TransactionTemplate(context.getBean(TestTransactionManager.class));
        when(context.getBean(SetmealDishMapper.class).getSetmealIdsByDishIds(Collections.singletonList(1L)))
                .thenReturn(Collections.singletonList(9L));
        caches.getCache("setmealCache").put(3L, "old-menu");
        caches.getCache("userSetmealCache").put(3L, "old-result");
    }

    @AfterEach
    void close() { context.close(); }

    @Test
    void linkedSetmealCachesAreInvalidatedOnlyAfterCommit() {
        transaction.execute(status -> {
            service.startOrStop(StatusConstant.DISABLE, 1L);
            verify(setmeals).update(argThat(s -> s.getId().equals(9L)
                    && s.getStatus().equals(StatusConstant.DISABLE)));
            assertCachesPresent();
            verifyNoInteractions(redis);
            return null;
        });
        verify(menuCache).invalidateDishes();
        assertNull(caches.getCache("setmealCache").get(3L));
        assertNull(caches.getCache("userSetmealCache").get(3L));
    }

    @Test
    void rollbackKeepsAllCaches() {
        transaction.execute(status -> {
            service.startOrStop(StatusConstant.DISABLE, 1L);
            status.setRollbackOnly();
            return null;
        });
        verifyNoInteractions(menuCache);
        assertCachesPresent();
    }

    @Test
    void enablingDishDoesNotEvictUnchangedSetmeals() {
        service.startOrStop(StatusConstant.ENABLE, 1L);
        verify(menuCache).invalidateDishes();
        verifyNoInteractions(setmeals);
        assertCachesPresent();
    }

    @Test
    void disablingUnrelatedDishDoesNotEvictSetmeals() {
        when(context.getBean(SetmealDishMapper.class).getSetmealIdsByDishIds(anyList()))
                .thenReturn(Collections.emptyList());
        service.startOrStop(StatusConstant.DISABLE, 1L);
        verify(menuCache).invalidateDishes();
        assertCachesPresent();
    }

    @Test
    void databaseFailureDoesNotEvictCaches() {
        doThrow(new IllegalStateException("database failure")).when(setmeals).update(any(Setmeal.class));
        assertThrows(IllegalStateException.class, () -> service.startOrStop(StatusConstant.DISABLE, 1L));
        verifyNoInteractions(menuCache);
        assertCachesPresent();
    }

    private void assertCachesPresent() {
        assertNotNull(caches.getCache("setmealCache").get(3L));
        assertNotNull(caches.getCache("userSetmealCache").get(3L));
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean com.sky.cache.MenuCache menuCache() { return mock(com.sky.cache.MenuCache.class); }
        @Bean DishService dishService() { return new DishServiceImpl(); }
        @Bean DishStatusCacheListener listener() { return new DishStatusCacheListener(); }
        @Bean DishMapper dishMapper() { return mock(DishMapper.class); }
        @Bean DishFlavorMapper dishFlavorMapper() { return mock(DishFlavorMapper.class); }
        @Bean SetmealDishMapper setmealDishMapper() { return mock(SetmealDishMapper.class); }
        @Bean SetmealMapper setmealMapper() { return mock(SetmealMapper.class); }
        @Bean RedisTemplate redisTemplate() { return mock(RedisTemplate.class); }
        @Bean CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("setmealCache", "userSetmealCache");
        }
        @Bean TestTransactionManager transactionManager() { return new TestTransactionManager(); }
    }

    // 使用 Spring 的真实事务同步/事件机制，数据库操作由 mock 隔离。
    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }
        @Override protected boolean isExistingTransaction(Object tx) { return Boolean.TRUE.equals(tx); }
        @Override protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition def) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
