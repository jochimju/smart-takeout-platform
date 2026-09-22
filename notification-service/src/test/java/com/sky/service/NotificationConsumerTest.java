package com.sky.service;

import com.sky.websocket.WebSocketServer;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationConsumerTest {
    private static final String EVENT = "{\"eventId\":\"evt-1\",\"eventType\":\"PAYMENT_SUCCEEDED\",\"aggregateType\":\"ORDER\",\"aggregateId\":\"1\",\"payload\":\"{\\\"type\\\":1}\"}";

    @Test
    void duplicateEventIsIgnoredAfterTheFirstDelivery() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("notification:event:evt-1"), eq("1"), any(Duration.class))).thenReturn(false);

        new NotificationConsumer(redis, mock(WebSocketServer.class)).consume(EVENT);

        verify(redis, never()).convertAndSend(anyString(), any());
    }

    @Test
    void failedFanoutRemovesClaimSoBrokerRedeliveryCanRetry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        @SuppressWarnings("unchecked") HashOperations<String, Object, Object> failures = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(failures);
        when(values.setIfAbsent(eq("notification:event:evt-1"), eq("1"), any(Duration.class))).thenReturn(true);
        when(redis.convertAndSend(anyString(), any())).thenThrow(new IllegalStateException("injected redis publish failure"));

        NotificationConsumer consumer = new NotificationConsumer(redis, mock(WebSocketServer.class));

        assertThrows(IllegalStateException.class, () -> consumer.consume(EVENT));
        verify(redis).delete("notification:event:evt-1");
        verify(failures).put(eq("notification:failures"), anyString(), eq(EVENT));
    }
}
