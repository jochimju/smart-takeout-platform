package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.sky.contract.notification.BusinessEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class BusinessEventOutbox {
    public static final String EXCHANGE = "sky.notification.events";
    public static final String ROUTING_KEY = "notification.order";
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private static final int LEASE_SECONDS = 30;
    private static final int CONFIRM_TIMEOUT_SECONDS = 5;

    public void record(String type, Object aggregateId, Object payload) {
        BusinessEvent event = BusinessEvent.builder().eventId(UUID.randomUUID().toString()).eventType(type)
                .aggregateType("ORDER").aggregateId(String.valueOf(aggregateId))
                .payload(JSON.toJSONString(payload)).occurredAt(LocalDateTime.now()).build();
        jdbc.update("insert into business_event_outbox(event_id,event_type,aggregate_type,aggregate_id,payload) values(?,?,?,?,?)",
                event.getEventId(), event.getEventType(), event.getAggregateType(), event.getAggregateId(), JSON.toJSONString(event));
    }

    @Scheduled(fixedDelayString = "${sky.outbox.publish-interval:1000}")
    public void publishPending() {
        List<Map<String,Object>> rows = jdbc.queryForList("select event_id,payload,retry_count from business_event_outbox " +
                "where (status=0 and next_retry_time<=now()) or (status=2 and delivery_lease_until<=now()) " +
                "order by create_time limit 100");
        for (Map<String,Object> row : rows) {
            String id = String.valueOf(row.get("event_id"));
            String deliveryToken = claim(id);
            if (deliveryToken == null) continue;
            try {
                publishConfirmed(id, String.valueOf(row.get("payload")));
                jdbc.update("update business_event_outbox set status=1,sent_time=now(),delivery_lease_until=null,last_error=null " +
                        "where event_id=? and status=2 and delivery_token=?", id, deliveryToken);
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                jdbc.update("update business_event_outbox set status=0,retry_count=retry_count+1," +
                                "last_error=?,delivery_lease_until=null,next_retry_time=date_add(now(),interval least(300,pow(2,retry_count)) second) " +
                                "where event_id=? and status=2 and delivery_token=?",
                        concise(ex), id, deliveryToken);
            }
        }
    }

    private String claim(String eventId) {
        String deliveryToken = UUID.randomUUID().toString();
        int claimed = jdbc.update("update business_event_outbox set status=2,delivery_token=?,delivery_lease_until=date_add(now(),interval ? second) " +
                        "where event_id=? and ((status=0 and next_retry_time<=now()) or (status=2 and delivery_lease_until<=now()))",
                deliveryToken, LEASE_SECONDS, eventId);
        return claimed == 1 ? deliveryToken : null;
    }

    private void publishConfirmed(String eventId, String payload) throws Exception {
        CorrelationData correlation = new CorrelationData(eventId);
        rabbit.convertAndSend(EXCHANGE, ROUTING_KEY, payload, correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!confirm.isAck()) throw new IllegalStateException("broker rejected event " + eventId + ": " + confirm.getReason());
        // A mandatory-message return receives a broker ACK too, so ACK alone is insufficient.
        if (correlation.getReturned() != null) throw new IllegalStateException("event is unroutable: " + eventId);
    }

    private static String concise(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage());
        return message.length() <= 512 ? message : message.substring(0, 512);
    }
}
