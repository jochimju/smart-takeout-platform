package com.sky.service.impl;

import com.rabbitmq.client.Channel;
import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.exception.OrderBusinessException;
import com.sky.listener.SeckillOrderListener;
import com.sky.service.SeckillService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeckillConsumerTest {
    SeckillService service=mock(SeckillService.class);
    SeckillAsyncStore store=mock(SeckillAsyncStore.class);
    Channel channel=mock(Channel.class);
    SeckillOrderListener listener=new SeckillOrderListener(service,store);
    SeckillOrderMessageDTO command=SeckillOrderMessageDTO.builder().orderNumber("SKtest").build();
    Message message() {var p=new MessageProperties();p.setDeliveryTag(3);return new Message(new byte[0],p);}
    @Test void successCommitsBeforeAck() throws Exception {
        listener.handle(command,channel,message());
        var sequence=inOrder(service,channel);sequence.verify(service).createSeckillOrder(command);sequence.verify(channel).basicAck(3,false);
    }
    @Test void durableBusinessFailurePrecedesAck() throws Exception {
        var error=new OrderBusinessException("红包已使用");doThrow(error).when(service).createSeckillOrder(command);
        listener.handle(command,channel,message());
        var sequence=inOrder(store,channel);sequence.verify(store).failed("SKtest",error,true);sequence.verify(channel).basicAck(3,false);
    }
    @Test void recoveryDatabaseFailureRetainsBrokerCopy() throws Exception {
        var error=new IllegalStateException("database offline");doThrow(error).when(service).createSeckillOrder(command);
        doThrow(error).when(store).failed("SKtest",error,false);
        listener.handle(command,channel,message());verify(channel).basicNack(3,false,true);verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void orphanMessageGoesToFailureQueueWithoutInfiniteRequeue() throws Exception {
        doThrow(new SeckillAsyncStore.MissingAcceptance("SKtest")).when(service).createSeckillOrder(command);
        listener.handle(command,channel,message());
        verify(channel).basicNack(3,false,false);verifyNoInteractions(store);
    }
    @Test void failedAckDoesNotCompensateCommittedOrder() throws Exception {
        doThrow(new java.io.IOException("channel closed")).when(channel).basicAck(3,false);
        assertThrows(java.io.IOException.class,()->listener.handle(command,channel,message()));verifyNoInteractions(store);
    }
}
