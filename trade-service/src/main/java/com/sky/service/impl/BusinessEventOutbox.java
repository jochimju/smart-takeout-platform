package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.sky.contract.notification.BusinessEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BusinessEventOutbox {
    public static final String EXCHANGE = "sky.notification.events";
    public static final String ROUTING_KEY = "notification.order";
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;

    public void record(String type, Object aggregateId, Object payload) {
        BusinessEvent event = BusinessEvent.builder().eventId(UUID.randomUUID().toString()).eventType(type)
                .aggregateType("ORDER").aggregateId(String.valueOf(aggregateId))
                .payload(JSON.toJSONString(payload)).occurredAt(LocalDateTime.now()).build();
        jdbc.update("insert into business_event_outbox(event_id,event_type,aggregate_type,aggregate_id,payload) values(?,?,?,?,?)",
                event.getEventId(), event.getEventType(), event.getAggregateType(), event.getAggregateId(), JSON.toJSONString(event));
    }

    @Scheduled(fixedDelayString = "${sky.outbox.publish-interval:1000}")
    public void publishPending() {
        List<Map<String,Object>> rows = jdbc.queryForList("select event_id,payload,retry_count from business_event_outbox where status=0 and next_retry_time<=now() order by create_time limit 100");
        for (Map<String,Object> row : rows) {
            String id = String.valueOf(row.get("event_id"));
            try {
                rabbit.invoke(ops -> {
                    ops.convertAndSend(EXCHANGE, ROUTING_KEY, row.get("payload"));
                    ops.waitForConfirmsOrDie(5000);
                    return true;
                });
                jdbc.update("update business_event_outbox set status=1,sent_time=now() where event_id=? and status=0", id);
            } catch (RuntimeException ex) {
                jdbc.update("update business_event_outbox set retry_count=retry_count+1,next_retry_time=date_add(now(),interval least(300,pow(2,retry_count)) second) where event_id=?", id);
            }
        }
    }
}
