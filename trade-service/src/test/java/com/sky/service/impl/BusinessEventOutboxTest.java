package com.sky.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BusinessEventOutboxTest {
    @Test
    void brokerNackReleasesLeaseAndSchedulesRetry() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row()));
        when(jdbc.update(startsWith("update business_event_outbox set status=2"), anyString(), anyInt(), eq("evt-1"))).thenReturn(1);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "injected nack"));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        new BusinessEventOutbox(jdbc, rabbit).publishPending();

        verify(jdbc).update(contains("status=0,retry_count=retry_count+1"),
                contains("injected nack"), eq("evt-1"), anyString());
        verify(jdbc, never()).update(startsWith("update business_event_outbox set status=1"), any(), any());
    }

    @Test
    void claimedEventIsMarkedSentOnlyAfterBrokerConfirm() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row()));
        when(jdbc.update(startsWith("update business_event_outbox set status=2"), anyString(), anyInt(), eq("evt-1"))).thenReturn(1);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        new BusinessEventOutbox(jdbc, rabbit).publishPending();

        verify(jdbc).update(contains("status=1,sent_time=now(),delivery_lease_until=null,last_error=null where event_id=? and status=2 and delivery_token=?"),
                eq("evt-1"), anyString());
        verify(jdbc, never()).update(startsWith("update business_event_outbox set status=0,retry_count=retry_count+1"), any(), any(), any());
    }

    @Test
    void returnedMessageIsRetriedEvenWhenBrokerAcknowledgesPublish() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row()));
        when(jdbc.update(startsWith("update business_event_outbox set status=2"), anyString(), anyInt(), eq("evt-1"))).thenReturn(1);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE",
                    BusinessEventOutbox.EXCHANGE, BusinessEventOutbox.ROUTING_KEY));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        new BusinessEventOutbox(jdbc, rabbit).publishPending();

        verify(jdbc).update(contains("status=0,retry_count=retry_count+1"),
                contains("unroutable"), eq("evt-1"), anyString());
        verify(jdbc, never()).update(startsWith("update business_event_outbox set status=1"), any(), any());
    }

    @Test
    void anotherPublisherLeasePreventsDuplicateSend() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row()));
        when(jdbc.update(startsWith("update business_event_outbox set status=2"), anyString(), anyInt(), eq("evt-1"))).thenReturn(0);

        new BusinessEventOutbox(jdbc, rabbit).publishPending();

        verifyNoInteractions(rabbit);
    }

    private Map<String, Object> row() {
        return Map.of("event_id", "evt-1", "payload", "{\"eventId\":\"evt-1\"}", "retry_count", 0);
    }
}
