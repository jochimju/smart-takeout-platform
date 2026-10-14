package com.sky.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Opt-in, bounded-cardinality diagnostics. No request identifiers or per-order logging. */
@Component
public class SeckillTiming {
    private static volatile MeterRegistry registry;
    private static final Map<String, Timer> TIMERS = new ConcurrentHashMap<>();
    private static final Map<String, io.micrometer.core.instrument.Counter> COUNTERS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Sample> CURRENT = new ThreadLocal<>();
    private static class Sample {
        final String operation;
        final Map<String, Long> phases = new LinkedHashMap<>();
        final Map<String, Long> events = new LinkedHashMap<>();
        String outcome = "done";
        long rootStarted;
        long completionStarted;
        Sample(String operation) { this.operation = operation; }
    }

    public SeckillTiming(MeterRegistry meters,
            @Value("${sky.seckill.profile-enabled:false}") boolean enabled) {
        TIMERS.clear();
        COUNTERS.clear();
        registry = enabled ? meters : null;
    }

    public static void root(String operation, Runnable work) {
        MeterRegistry meters = registry;
        if (meters == null) { work.run(); return; }
        Sample previous = CURRENT.get();
        Sample sample = new Sample(operation);
        CURRENT.set(sample);
        long started = System.nanoTime();
        sample.rootStarted = started;
        try { work.run(); }
        catch (RuntimeException | Error failure) { sample.outcome = "error"; throw failure; }
        finally {
            long finished = System.nanoTime();
            sample.phases.put("total", finished - started);
            if (sample.completionStarted != 0)
                sample.phases.put("transaction_cleanup", finished-sample.completionStarted);
            // Restore context before publishing diagnostics, even when business work failed.
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            for (var phase : sample.phases.entrySet()) {
                try {
                    String key = operation+":"+sample.outcome+":"+phase.getKey();
                    TIMERS.computeIfAbsent(key, ignored -> Timer.builder("seckill.profile")
                        .tags("operation", operation, "phase", phase.getKey(), "outcome", sample.outcome)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .distributionStatisticExpiry(java.time.Duration.ofMinutes(30))
                        .distributionStatisticBufferLength(1)
                        .register(meters)).record(phase.getValue(), TimeUnit.NANOSECONDS);
                } catch (RuntimeException ignored) { /* Diagnostics must never fail an order. */ }
            }
            for(var event:sample.events.entrySet()) {
                try {
                    String key=operation+":"+sample.outcome+":"+event.getKey();
                    COUNTERS.computeIfAbsent(key,ignored->io.micrometer.core.instrument.Counter.builder("seckill.profile.events")
                        .tags("operation",operation,"outcome",sample.outcome,"event",event.getKey()).register(meters)).increment(event.getValue());
                } catch(RuntimeException ignored) { /* Observability must not change business results. */ }
            }
        }
    }

    public static <T> T rootValue(String operation,Supplier<T> work) {
        if(registry==null)return work.get();
        var result=new java.util.concurrent.atomic.AtomicReference<T>();
        root(operation,()->result.set(work.get()));return result.get();
    }
    public static void count(String event,long amount) {
        Sample sample=CURRENT.get();if(sample!=null)sample.events.merge(event,amount,Long::sum);
    }

    public static <T> T measure(String phase, Supplier<T> work) {
        if (CURRENT.get() == null) return work.get();
        long started = System.nanoTime();
        try { return work.get(); }
        finally { record(phase, System.nanoTime() - started); }
    }
    public static void run(String phase, Runnable work) {
        measure(phase, () -> { work.run(); return null; });
    }
    public static void outcome(String value) {
        Sample sample = CURRENT.get();
        if (sample != null) sample.outcome = value;
    }
    public static void record(String phase, long elapsedNanos) {
        Sample sample = CURRENT.get();
        if (sample != null) sample.phases.merge(phase, elapsedNanos, Long::sum);
    }

    /** Register before business afterCommit callbacks: measured window ends before their work. */
    public static void transaction() {
        Sample sample = CURRENT.get();
        if (sample == null || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        sample.phases.put("transaction_setup", System.nanoTime()-sample.rootStarted);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            long beforeCommit;
            @Override public void beforeCommit(boolean readOnly) { beforeCommit = System.nanoTime(); }
            @Override public void afterCommit() {
                if (beforeCommit != 0) sample.phases.merge("commit_window", System.nanoTime()-beforeCommit, Long::sum);
            }
            @Override public void afterCompletion(int status) { sample.completionStarted = System.nanoTime(); }
        });
    }
}
