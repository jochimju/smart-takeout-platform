package com.sky.service.impl;

import com.sky.listener.OrderMessageListener;
import com.sky.service.OrderService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class TimeoutConsumerTest {
    final OrderService orders=mock(OrderService.class);
    final TimeoutFailureService failures=mock(TimeoutFailureService.class);
    final Channel channel=mock(Channel.class);
    final OrderMessageListener listener=new OrderMessageListener(orders,failures);
    Message message() { MessageProperties p=new MessageProperties(); p.setDeliveryTag(3); return new Message(new byte[0],p); }

    @Test void acknowledgesOnlyAfterBusinessReturns() throws Exception {
        listener.handleTimeoutOrder("order",channel,message());
        InOrder calls=inOrder(orders,channel);
        calls.verify(orders).cancelTimeoutOrder("order"); calls.verify(channel).basicAck(3,false);
        verifyNoInteractions(failures);
    }
    @Test void persistsRecoveryBeforeAcknowledgingFailedBusiness() throws Exception {
        RuntimeException error=new RuntimeException("stock unavailable");
        doThrow(error).when(orders).cancelTimeoutOrder("order");
        listener.handleTimeoutOrder("order",channel,message());
        InOrder calls=inOrder(failures,channel);
        calls.verify(failures).record("order",error); calls.verify(channel).basicAck(3,false);
    }
    @Test void unavailableRecoveryDatabaseKeepsBrokerCopy() throws Exception {
        doThrow(new RuntimeException("database offline")).when(orders).cancelTimeoutOrder("order");
        doThrow(new RuntimeException("database offline")).when(failures).record(anyString(),any());
        listener.handleTimeoutOrder("order",channel,message());
        verify(channel).basicNack(3,false,true); verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void failedAckDoesNotScheduleCompletedBusinessAgain() throws Exception {
        doThrow(new java.io.IOException("channel closed")).when(channel).basicAck(3,false);
        assertThrows(java.io.IOException.class,()->listener.handleTimeoutOrder("order",channel,message()));
        verifyNoInteractions(failures);
    }
}
