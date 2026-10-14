package com.sky.service.impl;

import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.exception.OrderBusinessException;
import com.sky.listener.SeckillOrderListener;
import com.sky.service.SeckillService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SeckillBatchListenerTest {
    final SeckillAsyncStore store=mock(SeckillAsyncStore.class);final SeckillBatchCoordinator batches=mock(SeckillBatchCoordinator.class);
    final Channel channel=mock(Channel.class);final MessageConverter converter=mock(MessageConverter.class);
    SeckillOrderListener listener(){var listener=new SeckillOrderListener(mock(SeckillService.class),store);ReflectionTestUtils.setField(listener,"batches",batches);ReflectionTestUtils.setField(listener,"converter",converter);return listener;}
    Message message(long tag){var props=new MessageProperties();props.setDeliveryTag(tag);var msg=new Message(new byte[0],props);when(converter.fromMessage(msg)).thenReturn(SeckillOrderMessageDTO.builder().orderNumber("SK"+tag).build());return msg;}
    @Test void individualAcknowledgmentsFollowBatchCommitReturn()throws Exception {
        var messages=List.of(message(1),message(2));when(batches.fulfill(anyList())).thenReturn(Map.of());listener().handleBatch(messages,channel);
        var ordered=inOrder(batches,channel);ordered.verify(batches).fulfill(anyList());ordered.verify(channel).basicAck(1,false);ordered.verify(channel).basicAck(2,false);
    }
    @Test void failedAckNeverCompensatesCommittedBatch()throws Exception {
        var messages=List.of(message(1),message(2));when(batches.fulfill(anyList())).thenReturn(Map.of());
        doThrow(new java.io.IOException("channel closed")).when(channel).basicAck(1,false);
        assertThrows(java.io.IOException.class,()->listener().handleBatch(messages,channel));verifyNoInteractions(store);
    }
    @Test void failedFailurePersistenceKeepsOnlyAffectedBrokerCopy()throws Exception {
        var messages=List.of(message(1),message(2));var error=new OrderBusinessException("红包不可用");when(batches.fulfill(anyList())).thenReturn(Map.of("SK2",error));
        doThrow(new IllegalStateException("database offline")).when(store).failed("SK2",error,true);listener().handleBatch(messages,channel);
        verify(channel).basicAck(1,false);verify(channel).basicNack(2,false,true);verify(channel,never()).basicAck(2,false);
    }
    @Test void malformedCommandDoesNotPreventGoodDeliveryAcknowledgment()throws Exception {
        var messages=List.of(message(1),message(2));when(converter.fromMessage(messages.get(0))).thenReturn("invalid");when(batches.fulfill(anyList())).thenReturn(Map.of());
        listener().handleBatch(messages,channel);verify(channel).basicNack(1,false,false);verify(channel).basicAck(2,false);
    }
}
