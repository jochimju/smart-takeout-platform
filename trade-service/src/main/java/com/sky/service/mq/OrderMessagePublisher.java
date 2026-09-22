package com.sky.service.mq;

import com.alibaba.fastjson.JSON;
import com.sky.constant.MqConstant;
import com.sky.dto.OrderSubmitMessageDTO;
import com.sky.entity.MqFailMessage;
import com.sky.mapper.MqFailMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * A transactional hand-off to RabbitMQ.  The retry table is written in the
 * same transaction as the acceptance/order record; publishing happens only
 * after commit, so a consumer never observes a command that was rolled back.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderMessagePublisher {
    private final RabbitTemplate rabbit;
    private final MqFailMessageMapper failures;

    public void submitAfterCommit(OrderSubmitMessageDTO command) {
        enqueue(MqConstant.ORDER_EXCHANGE, MqConstant.ORDER_SUBMIT_ROUTING_KEY, command);
    }

    /** The delay queue has a 15-minute TTL and routes expiry to its DLQ. */
    public void timeoutAfterCommit(String orderNumber) {
        enqueue(MqConstant.ORDER_DELAY_EXCHANGE, MqConstant.ORDER_DELAY_ROUTING_KEY, orderNumber);
    }

    private void enqueue(String exchange, String routingKey, Object payload) {
        MqFailMessage pending = MqFailMessage.builder()
                .exchangeName(exchange).routingKey(routingKey)
                .messageBody(JSON.toJSONString(payload)).failReason("pending publish")
                .status(0).retryCount(0).createTime(LocalDateTime.now()).build();
        failures.insert(pending);
        Runnable publish = () -> publish(pending);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
    }

    private void publish(MqFailMessage pending) {
        try {
            CorrelationData correlation = new CorrelationData("command:" + pending.getId());
            rabbit.convertAndSend(pending.getExchangeName(), pending.getRoutingKey(), payloadFor(pending), message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setMessageId(String.valueOf(pending.getId()));
                        return message;
                    }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException("broker did not confirm/rout command");
            }
            failures.markPublished(pending.getId(), LocalDateTime.now());
        } catch (Exception ex) {
            // The durable row remains retryable.  Do not throw from afterCommit:
            // the HTTP request has already been accepted successfully.
            log.warn("Order command publish deferred, id={}", pending.getId(), ex);
        }
    }

    private Object payloadFor(MqFailMessage pending) {
        if (MqConstant.ORDER_SUBMIT_ROUTING_KEY.equals(pending.getRoutingKey())) {
            return JSON.parseObject(pending.getMessageBody(), OrderSubmitMessageDTO.class);
        }
        if (MqConstant.ORDER_DELAY_ROUTING_KEY.equals(pending.getRoutingKey())) {
            return JSON.parseObject(pending.getMessageBody(), String.class);
        }
        return pending.getMessageBody();
    }
}
