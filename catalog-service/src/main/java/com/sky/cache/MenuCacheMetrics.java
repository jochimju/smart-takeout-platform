package com.sky.cache;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** 单实例、进程启动以来累计。请求首次读取决定命中率，预热单独计数。 */
@Component
public class MenuCacheMetrics {
    private final Map<String, Map<String, LongAdder>> counters = new ConcurrentHashMap<>();
    public void increment(String kind, String name) {
        counters.computeIfAbsent(kind, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(name, k -> new LongAdder()).increment();
    }
    public Map<String, Map<String, Number>> snapshot() {
        Map<String, Map<String, Number>> result = new TreeMap<>();
        for (String kind : Arrays.asList("dish", "setmeal")) {
            Map<String, Number> values = new TreeMap<>();
            Map<String, LongAdder> source = counters.getOrDefault(kind, Collections.emptyMap());
            for (String name : Arrays.asList("requests", "hits", "misses", "loads", "contentions", "timeouts", "errors", "warmups", "warmupLoads", "warmupErrors")) {
                values.put(name, source.containsKey(name) ? source.get(name).sum() : 0L);
            }
            long reads = values.get("hits").longValue() + values.get("misses").longValue();
            values.put("hitRate", reads == 0 ? 0.0 : values.get("hits").doubleValue() / reads);
            result.put(kind, values);
        }
        return result;
    }
}