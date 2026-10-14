package com.sky.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable, at-least-once invalidation; repeated INCR only discards additional cache entries. */
@Component
@Slf4j
public class MenuCacheInvalidationOutbox {
    private final JdbcTemplate jdbc;
    private final MenuCache cache;
    private final TransactionTemplate independent;

    public MenuCacheInvalidationOutbox(JdbcTemplate jdbc, MenuCache cache, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.cache = cache;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String kind) {
        if (!("dish".equals(kind) || "setmeal".equals(kind))) throw new IllegalArgumentException("invalid cache kind");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("cache invalidation must share the business transaction");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into menu_cache_invalidation(event_id,cache_kind) values(?,?)", id, kind);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                // Failure here must not turn an already committed business update into an HTTP failure.
                try { deliver(id); }
                catch (RuntimeException ex) { log.warn("Menu invalidation {} remains durable for retry", id, ex); }
            }
        });
    }

    @Scheduled(fixedDelayString = "${sky.cache.invalidation-retry-interval:1000}")
    public void retryPending() {
        try {
            List<String> ids = jdbc.queryForList("select event_id from menu_cache_invalidation "
                    + "where (status=0 and next_retry_time<=CURRENT_TIMESTAMP) "
                    + "or (status=2 and lease_until<=CURRENT_TIMESTAMP) order by create_time limit 100", String.class);
            for (String id : ids) if (!deliver(id)) break; // Back off when Redis is unavailable.
        } catch (RuntimeException ex) { log.warn("Menu invalidation retry scan failed; retrying next cycle", ex); }
    }

    private boolean deliver(String id) {
        String token = UUID.randomUUID().toString();
        String kind = independent.execute(tx -> {
            int claimed = jdbc.update("update menu_cache_invalidation set status=2,lease_token=?,"
                    + "lease_until=? where event_id=? and ((status=0 and next_retry_time<=CURRENT_TIMESTAMP) "
                    + "or (status=2 and lease_until<=CURRENT_TIMESTAMP))",
                    token, Timestamp.from(Instant.now().plusSeconds(30)), id);
            return claimed == 1 ? jdbc.queryForObject("select cache_kind from menu_cache_invalidation where event_id=?",
                    String.class, id) : null;
        });
        if (kind == null) return true;
        try {
            if ("dish".equals(kind)) cache.invalidateDishes(); else cache.invalidateSetmeals();
            independent.executeWithoutResult(tx -> jdbc.update("update menu_cache_invalidation set status=1,"
                    + "completed_time=CURRENT_TIMESTAMP,lease_until=null,last_error=null "
                    + "where event_id=? and status=2 and lease_token=?", id, token));
            return true;
        } catch (RuntimeException ex) {
            log.warn("Menu invalidation {} failed; keeping retry task", id, ex);
            independent.executeWithoutResult(tx -> {
                Integer attempts = jdbc.queryForObject("select retry_count from menu_cache_invalidation where event_id=?",
                        Integer.class, id);
                long delay = Math.min(300, 1L << Math.min(attempts == null ? 0 : attempts, 9));
                String error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                jdbc.update("update menu_cache_invalidation set status=0,retry_count=retry_count+1,"
                        + "next_retry_time=?,last_error=?,lease_until=null where event_id=? and status=2 and lease_token=?",
                        Timestamp.from(Instant.now().plusSeconds(delay)), error.substring(0, Math.min(512, error.length())), id, token);
            });
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${sky.cache.invalidation-cleanup-interval:3600000}")
    public void cleanupCompleted() {
        jdbc.update("delete from menu_cache_invalidation where status=1 and completed_time<? limit 1000",
                Timestamp.from(Instant.now().minusSeconds(7 * 86400L)));
    }

    public java.util.Map<String, Object> summary() {
        return jdbc.queryForMap("select count(*) as outstanding,coalesce(sum(retry_count),0) as retry_attempts,"
                + "min(create_time) as oldest_created_time from menu_cache_invalidation where status<>1");
    }
}
