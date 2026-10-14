package com.sky.service.impl;

import com.sky.cache.MenuCache;
import com.sky.cache.MenuCacheInvalidationOutbox;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MenuCacheInvalidationOutboxTest {
    JdbcTemplate jdbc;
    MenuCache cache;
    DataSourceTransactionManager manager;
    TransactionTemplate transaction;
    MenuCacheInvalidationOutbox outbox;

    @BeforeEach void setup() {
        jdbc = CacheOutboxDatabase.create();
        jdbc.execute("create table business_fixture(id int primary key)");
        cache = mock(MenuCache.class);
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
        transaction = new TransactionTemplate(manager);
        outbox = new MenuCacheInvalidationOutbox(jdbc, cache, manager);
    }
    int count(String condition) {
        return jdbc.queryForObject("select count(*) from menu_cache_invalidation where " + condition, Integer.class);
    }
    void due() { jdbc.update("update menu_cache_invalidation set next_retry_time=TIMESTAMP '2000-01-01 00:00:00',lease_until=TIMESTAMP '2000-01-01 00:00:00'"); }

    @Test void committedWriteSurvivesRedisFailureAndNewWorkerRetries() {
        doThrow(new IllegalStateException("Redis unavailable")).when(cache).invalidateDishes();
        assertDoesNotThrow(() -> transaction.executeWithoutResult(tx -> {
            jdbc.update("insert into business_fixture values(1)"); outbox.record("dish");
            assertEquals(1, count("status=0")); verifyNoInteractions(cache);
        }));
        assertEquals(1, jdbc.queryForObject("select count(*) from business_fixture", Integer.class));
        assertEquals(1, count("status=0 and retry_count=1 and last_error is not null"));
        doNothing().when(cache).invalidateDishes(); due();
        new MenuCacheInvalidationOutbox(jdbc, cache, manager).retryPending();
        assertEquals(1, count("status=1")); verify(cache, times(2)).invalidateDishes();
    }
    @Test void rollbackRemovesBothBusinessWriteAndTask() {
        transaction.executeWithoutResult(tx -> {
            jdbc.update("insert into business_fixture values(1)"); outbox.record("setmeal"); tx.setRollbackOnly();
        });
        assertEquals(0, count("1=1"));
        assertEquals(0, jdbc.queryForObject("select count(*) from business_fixture", Integer.class));
        outbox.retryPending(); verifyNoInteractions(cache);
    }
    @Test void taskInsertFailureRollsBackBusinessWrite() {
        jdbc.execute("drop table menu_cache_invalidation");
        assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(tx -> {
            jdbc.update("insert into business_fixture values(1)"); outbox.record("dish");
        }));
        assertEquals(0, jdbc.queryForObject("select count(*) from business_fixture", Integer.class));
        verifyNoInteractions(cache);
    }
    @Test void processExitBeforeDeliveryLeavesCommittedTaskForRecovery() {
        // A committed row is the exact durable state left by exit before afterCommit delivery.
        jdbc.update("insert into menu_cache_invalidation(event_id,cache_kind) values('exit','setmeal')");
        new MenuCacheInvalidationOutbox(jdbc, cache, manager).retryPending();
        verify(cache).invalidateSetmeals(); assertEquals(1, count("status=1"));
    }
    @Test void abandonedLeaseIsRecoveredButLiveLeaseIsNot() {
        jdbc.update("insert into menu_cache_invalidation(event_id,cache_kind,status,lease_token,lease_until) "
                + "values('lease','dish',2,'dead',TIMESTAMP '2099-01-01 00:00:00')");
        outbox.retryPending(); verifyNoInteractions(cache);
        due(); outbox.retryPending(); verify(cache).invalidateDishes(); assertEquals(1, count("status=1"));
    }
    @Test void failedAcknowledgementAllowsSafeRedelivery() {
        JdbcTemplate fault = spy(jdbc);
        doThrow(new IllegalStateException("ack unavailable")).when(fault).update(
                startsWith("update menu_cache_invalidation set status=1"), anyString(), anyString());
        MenuCacheInvalidationOutbox worker = new MenuCacheInvalidationOutbox(fault, cache, manager);
        transaction.executeWithoutResult(tx -> worker.record("setmeal"));
        assertEquals(1, count("status=0")); due(); outbox.retryPending();
        verify(cache, times(2)).invalidateSetmeals(); assertEquals(1, count("status=1"));
    }
    @Test void twoWorkersCannotOwnTheSameLiveLease() throws Exception {
        jdbc.update("insert into menu_cache_invalidation(event_id,cache_kind) values('parallel','dish')");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return null; })
                .when(cache).invalidateDishes();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(outbox::retryPending);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            pool.submit(new MenuCacheInvalidationOutbox(jdbc, cache, manager)::retryPending).get(5, TimeUnit.SECONDS);
            release.countDown(); first.get(5, TimeUnit.SECONDS);
            verify(cache, times(1)).invalidateDishes(); assertEquals(1, count("status=1"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void rejectsNonTransactionalAndUnknownTasks() {
        assertThrows(IllegalStateException.class, () -> outbox.record("dish"));
        assertThrows(IllegalArgumentException.class, () -> outbox.record("unknown"));
    }
    @Test void retryStateDatabaseFailureLeavesLeaseRecoverable() {
        JdbcTemplate fault = spy(jdbc);
        doThrow(new IllegalStateException("retry database unavailable")).when(fault).update(
                startsWith("update menu_cache_invalidation set status=0"), any(java.sql.Timestamp.class),
                anyString(), anyString(), anyString());
        doThrow(new IllegalStateException("Redis unavailable")).when(cache).invalidateDishes();
        var worker = new MenuCacheInvalidationOutbox(fault, cache, manager);
        assertDoesNotThrow(() -> transaction.executeWithoutResult(tx -> worker.record("dish")));
        assertEquals(1, count("status=2"));
        doNothing().when(cache).invalidateDishes(); due(); outbox.retryPending();
        assertEquals(1, count("status=1"));
    }
    @Test void cleanupRemovesOnlyOldCompletedTasks() {
        jdbc.update("insert into menu_cache_invalidation(event_id,cache_kind,status,completed_time) values('old','dish',1,TIMESTAMP '2000-01-01 00:00:00')");
        jdbc.update("insert into menu_cache_invalidation(event_id,cache_kind) values('pending','dish')");
        outbox.cleanupCompleted(); assertEquals(1, count("status=0")); assertEquals(0, count("status=1"));
    }
}
