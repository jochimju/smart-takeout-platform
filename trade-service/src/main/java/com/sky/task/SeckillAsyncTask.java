package com.sky.task;

import com.alibaba.fastjson.JSON;
import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.mapper.SeckillActivityMapper;
import com.sky.service.impl.SeckillAsyncStore;
import com.sky.service.impl.SeckillCache;
import com.sky.vo.OrderSubmitVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A task is removed from Redis only after acceptance and outbox commit together. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillAsyncTask {
    private final SeckillActivityMapper activities;
    private final SeckillCache cache;
    private final SeckillAsyncStore store;

    @Scheduled(fixedDelayString="${sky.seckill.bridge-ms:100}", scheduler="seckillScheduler")
    public void bridge() {
        com.sky.observability.SeckillTiming.root("bridge_cycle",this::bridgeActivities);
    }
    private void bridgeActivities() {
        for(Long activity:activities.allIds()) {
            try {
                var commands=new java.util.ArrayList<SeckillOrderMessageDTO>();
                for(var task:com.sky.observability.SeckillTiming.measure("pending_scan",()->cache.pending(activity)).entrySet()) {
                    try {
                        var envelope=JSON.parseObject(task.getValue());
                        SeckillOrderMessageDTO command=JSON.parseObject(envelope.getString("command"),SeckillOrderMessageDTO.class);
                        if(!activity.equals(command.getActivityId()) || !task.getKey().equals(command.getOrderNumber()))
                            throw new IllegalStateException("Redis task identity mismatch");
                        command.setPrice(envelope.getBigDecimal("price"));
                        command.setAcceptedAt(envelope.getLong("acceptedAt"));
                        command.setBucketId(envelope.getInteger("bucketId"));
                        command.setBucketCount(envelope.getInteger("bucketCount"));
                        commands.add(command);
                    } catch(Exception failure) { log.warn("Seckill handoff retained: {}",task.getKey(),failure); }
                }
                var records=com.sky.observability.SeckillTiming.measure("request_validation",()->cache.findBatch(activity,commands));
                var validated=new java.util.ArrayList<SeckillOrderMessageDTO>();
                for(var command:commands) {
                    var record=records.get(command.getOrderNumber());
                    if(record!=null && command.getOrderNumber().equals(record.getOrderNumber()))validated.add(command);
                    else log.warn("Incomplete Redis acceptance retained: {}",command.getOrderNumber());
                }
                com.sky.observability.SeckillTiming.count("commands_checked",commands.size());
                handOffBatch(activity,validated);
            } catch(Exception failure) { log.warn("Seckill bridge unavailable: {}",activity,failure); }
        }
    }

    private void handOffBatch(Long activity,java.util.List<SeckillOrderMessageDTO> commands) {
        if(commands.isEmpty()) return;
        try {
            com.sky.observability.SeckillTiming.root("bridge_batch", () -> store.acceptBatch(commands));
            cache.handedOffBatch(activity,commands.stream().map(SeckillOrderMessageDTO::getOrderNumber).toList());
        } catch(SeckillAsyncStore.AcceptanceConflict conflict) {
            if(commands.size()==1) { log.warn("Conflicting Redis task retained: {}",commands.get(0).getOrderNumber(),conflict); return; }
            int middle=commands.size()/2;
            handOffBatch(activity,commands.subList(0,middle));
            handOffBatch(activity,commands.subList(middle,commands.size()));
        } catch(Exception failure) { log.warn("Seckill batch retained: activity={}, size={}",activity,commands.size(),failure); }
    }

    @Scheduled(fixedDelayString="${sky.seckill.recovery-ms:1000}", scheduler="seckillScheduler")
    public void recover() {
        for(var row:store.ready()) {
            try { store.republishOrExpire((String)row.get("order_number")); }
            catch(Exception failure) { log.warn("Seckill recovery deferred: {}",row.get("order_number"),failure); }
        }
    }

    @Scheduled(fixedDelayString="${sky.seckill.cache-sync-ms:100}", scheduler="seckillCacheScheduler")
    public void syncCacheEvents() {
        com.sky.observability.SeckillTiming.root("cache_sync",this::syncCacheBatch);
    }
    private void syncCacheBatch() {
        var events=store.events();if(events.isEmpty())return;
        var completed=new java.util.ArrayList<java.util.Map<String,Object>>();
        try {
            for(var event:events)if("FAILED".equals(event.get("state")) && String.valueOf(event.get("last_error")).contains("库存核对失败"))
                cache.pause(((Number)event.get("activity_id")).longValue());
            var results=com.sky.observability.SeckillTiming.measure("redis_pipeline",()->cache.finishBatch(events));
            for(int i=0;i<events.size();i++) {
                var event=events.get(i);
                if(results.get(i))completed.add(event);
                else store.eventRetry((String)event.get("order_number"),(String)event.get("state"));
            }
            // Matching state prevents deleting a newer cancellation after an old success reply.
            store.eventsDone(completed);
            com.sky.observability.SeckillTiming.count("events_completed",completed.size());
        } catch(Exception failure) {
            // Redis may have completed only a prefix: retain the whole durable batch for idempotent replay.
            log.warn("Seckill cache batch retained: size={}",events.size(),failure);
        }
    }
}
