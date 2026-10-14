package com.sky.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SeckillTimingTest {
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    @AfterEach void reset() {
        new SeckillTiming(meters, false);
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
        meters.close();
    }
    @Test void disabledDoesNotChangeResultsOrRecordMetrics() {
        new SeckillTiming(meters, false);
        int[] value = {0};
        SeckillTiming.root("consume", () -> value[0] = SeckillTiming.measure("quote", () -> 42));
        assertEquals(42, value[0]); assertTrue(meters.getMeters().isEmpty());
    }
    @Test void failuresPropagateAndThreadContextDoesNotLeak() {
        new SeckillTiming(meters, true);
        RuntimeException failure = new RuntimeException("business failure");
        assertSame(failure, assertThrows(RuntimeException.class, () ->
            SeckillTiming.root("consume", () -> SeckillTiming.run("body", () -> { throw failure; }))));
        assertEquals(1, meters.get("seckill.profile").tags("operation","consume","phase","total","outcome","error").timer().count());
        SeckillTiming.run("outside", () -> {});
        assertNull(meters.find("seckill.profile").tag("phase","outside").timer());
    }
    @Test void commitWindowAndPostCommitWorkStaySeparate() {
        new SeckillTiming(meters, true);
        TransactionSynchronizationManager.initSynchronization();
        SeckillTiming.root("consume", () -> {
            SeckillTiming.transaction();
            SeckillTiming.run("body", () -> SeckillTiming.outcome("created"));
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(callback -> callback.beforeCommit(false));
            callbacks.forEach(callback -> callback.afterCommit());
            SeckillTiming.record("timeout_publish", 1000);
            callbacks.forEach(callback -> callback.afterCompletion(0));
        });
        assertEquals(1, meters.get("seckill.profile").tags("phase","commit_window","outcome","created").timer().count());
        assertEquals(1000, meters.get("seckill.profile").tags("phase","timeout_publish","outcome","created").timer().totalTime(TimeUnit.NANOSECONDS));
        assertEquals(1, meters.get("seckill.profile").tags("phase","transaction_setup","outcome","created").timer().count());
        assertEquals(1, meters.get("seckill.profile").tags("phase","transaction_cleanup","outcome","created").timer().count());
    }
}
