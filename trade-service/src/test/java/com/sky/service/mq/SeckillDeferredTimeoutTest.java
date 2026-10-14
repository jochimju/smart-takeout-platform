package com.sky.service.mq;

import com.sky.constant.MqConstant;
import com.sky.entity.MqFailMessage;
import com.sky.mapper.MqFailMessageMapper;
import com.sky.task.MqFailMessageRetryTask;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeckillDeferredTimeoutTest {
    @Test void blockedNetworkSendDoesNotBlockSettlementOfEarlierConfirm() throws Exception {
        var rabbit=mock(RabbitTemplate.class);var store=mock(SeckillPublishStore.class);var task=new MqFailMessageRetryTask();
        ReflectionTestUtils.setField(task,"rabbitTemplate",rabbit);ReflectionTestUtils.setField(task,"seckillPublishStore",store);
        var first=MqFailMessage.builder().id(1L).exchangeName("exchange").routingKey("seckill.order").messageBody("{}").build();
        var second=MqFailMessage.builder().id(2L).exchangeName("exchange").routingKey("seckill.order").messageBody("{}").build();
        when(store.claim(false,200)).thenReturn(List.of(first,second));
        var blocked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call->{CorrelationData data=call.getArgument(4);if(data.getId().equals("seckill:2")){blocked.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));}data.getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
            .when(rabbit).convertAndSend(anyString(),anyString(),any(),any(MessagePostProcessor.class),any(CorrelationData.class));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {var send=pool.submit(task::publishSeckillPending);assertTrue(blocked.await(5,java.util.concurrent.TimeUnit.SECONDS));
            pool.submit(task::flushSeckillConfirmations).get(2,java.util.concurrent.TimeUnit.SECONDS);
            verify(store).complete(argThat(rows->rows.size()==1 && rows.get(0).id()==1));
            release.countDown();send.get(5,java.util.concurrent.TimeUnit.SECONDS);task.flushSeckillConfirmations();
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void futureDeadlineUsesRemainingTtlInsteadOfImmediateCancellation() {
        var rabbit=mock(RabbitTemplate.class);var store=mock(SeckillPublishStore.class);var task=new MqFailMessageRetryTask();
        ReflectionTestUtils.setField(task,"rabbitTemplate",rabbit);ReflectionTestUtils.setField(task,"seckillPublishStore",store);
        when(store.claim(true,200)).thenReturn(List.of(MqFailMessage.builder().id(3L).exchangeName(MqConstant.ORDER_DELAY_EXCHANGE).routingKey(MqConstant.SECKILL_TIMEOUT_ROUTING_KEY).messageBody("\"SK3\"").deliveryDeadline(LocalDateTime.now().plusMinutes(15)).build()));
        doAnswer(call->{var processor=(MessagePostProcessor)call.getArgument(3);var message=processor.postProcessMessage(new org.springframework.amqp.core.Message(new byte[0],new org.springframework.amqp.core.MessageProperties()));
            assertNull(message.getMessageProperties().getExpiration());
            ((CorrelationData)call.getArgument(4)).getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
            .when(rabbit).convertAndSend(eq(MqConstant.ORDER_DELAY_EXCHANGE),eq(TimeoutRouting.route(900000)),any(),any(MessagePostProcessor.class),any(CorrelationData.class));
        task.publishSeckillTimeouts();verify(rabbit).convertAndSend(eq(MqConstant.ORDER_DELAY_EXCHANGE),eq(TimeoutRouting.route(900000)),eq("SK3"),any(MessagePostProcessor.class),any(CorrelationData.class));
    }
    @Test void failedConfirmBatchCommitIsRetainedAndNotSettledTwice() {
        var rabbit=mock(RabbitTemplate.class);var store=mock(SeckillPublishStore.class);var task=new MqFailMessageRetryTask();
        ReflectionTestUtils.setField(task,"rabbitTemplate",rabbit);ReflectionTestUtils.setField(task,"seckillPublishStore",store);
        var pending=MqFailMessage.builder().id(9L).claimToken("claim").routingKey(MqConstant.SECKILL_TIMEOUT_ROUTING_KEY)
            .exchangeName(MqConstant.ORDER_DELAY_EXCHANGE).messageBody("\"SKretained\"").build();
        when(store.claim(true,200)).thenReturn(List.of(pending));
        doAnswer(call->{CorrelationData data=call.getArgument(4);data.getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
            .when(rabbit).convertAndSend(anyString(),anyString(),any(),any(MessagePostProcessor.class),any(CorrelationData.class));
        doThrow(new IllegalStateException("database offline")).doNothing().when(store).complete(anyList());
        task.publishSeckillTimeouts();task.flushSeckillConfirmations();task.flushSeckillConfirmations();task.flushSeckillConfirmations();
        verify(store,times(2)).complete(argThat(rows->rows.size()==1 && rows.get(0).id()==9L && rows.get(0).acknowledged()));
    }
    @Test void deferredTimeoutOnlyWritesDeadlineAndNeverSendsOrRegistersCallback() {
        var rabbit=mock(RabbitTemplate.class);var mapper=mock(MqFailMessageMapper.class);
        var publisher=new OrderMessagePublisher(rabbit,mapper);var deadline=LocalDateTime.now().plusMinutes(15);
        TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            publisher.timeoutDeferred("SKdeadline",deadline);
            var capture=org.mockito.ArgumentCaptor.forClass(MqFailMessage.class);verify(mapper).insert(capture.capture());
            assertEquals(deadline,capture.getValue().getDeliveryDeadline());
            assertTrue(capture.getValue().getAvailableAt().isBefore(deadline));
            assertEquals(MqConstant.SECKILL_TIMEOUT_ROUTING_KEY,capture.getValue().getRoutingKey());
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());verifyNoInteractions(rabbit);
        } finally { TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    @Test void duePublisherAcknowledgesDurableRowAfterBrokerConfirmation() {
        var rabbit=mock(RabbitTemplate.class);var mapper=mock(MqFailMessageMapper.class);var task=new MqFailMessageRetryTask();
        ReflectionTestUtils.setField(task,"rabbitTemplate",rabbit);ReflectionTestUtils.setField(task,"mqFailMessageMapper",mapper);
        var store=mock(SeckillPublishStore.class);ReflectionTestUtils.setField(task,"seckillPublishStore",store);
        var pending=MqFailMessage.builder().id(8L).routingKey(MqConstant.SECKILL_TIMEOUT_ROUTING_KEY)
            .exchangeName(MqConstant.ORDER_DELAY_EXCHANGE).messageBody("\"SKdue\"").build();
        when(store.claim(true,200)).thenReturn(List.of(pending));
        doAnswer(call -> { CorrelationData data=call.getArgument(4);data.getFuture().complete(new CorrelationData.Confirm(true,null));return null; })
            .when(rabbit).convertAndSend(anyString(),anyString(),any(),any(MessagePostProcessor.class),any(CorrelationData.class));
        task.publishSeckillTimeouts();verify(store,never()).complete(anyList());task.flushSeckillConfirmations();
        verify(store).complete(argThat(rows->rows.size()==1 && rows.get(0).acknowledged() && rows.get(0).id()==8L));
        verify(mapper,never()).markSuccess(any(),any());
    }
    @Test void brokerFailureKeepsTimeoutRetryableInsteadOfExhaustingCreationRetryLimit() {
        var rabbit=mock(RabbitTemplate.class);var mapper=mock(MqFailMessageMapper.class);var task=new MqFailMessageRetryTask();
        ReflectionTestUtils.setField(task,"rabbitTemplate",rabbit);ReflectionTestUtils.setField(task,"mqFailMessageMapper",mapper);
        var store=mock(SeckillPublishStore.class);ReflectionTestUtils.setField(task,"seckillPublishStore",store);
        var pending=MqFailMessage.builder().id(8L).routingKey(MqConstant.SECKILL_TIMEOUT_ROUTING_KEY)
            .exchangeName(MqConstant.ORDER_DELAY_EXCHANGE).messageBody("\"SKdue\"").build();
        when(store.claim(true,200)).thenReturn(List.of(pending));
        doThrow(new IllegalStateException("broker unavailable")).when(rabbit).convertAndSend(anyString(),anyString(),any(),any(MessagePostProcessor.class),any(CorrelationData.class));
        task.publishSeckillTimeouts();task.flushSeckillConfirmations();
        verify(store).complete(argThat(rows->rows.size()==1 && rows.get(0).timeout() && !rows.get(0).acknowledged()));
        verify(mapper,never()).markDead(any(),any(),any());
    }
}
