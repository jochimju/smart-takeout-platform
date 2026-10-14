package com.sky.service.mq;

import com.alibaba.fastjson.JSON;
import com.sky.constant.MqConstant;
import com.sky.dto.OrderSubmitMessageDTO;
import com.sky.entity.MqFailMessage;
import com.sky.observability.SeckillTiming;
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

    /** 提交建单命令：调用方事务先保存消息记录，提交后才允许消费者看到命令。 */
    public void submitAfterCommit(OrderSubmitMessageDTO command) {
        enqueue(MqConstant.ORDER_EXCHANGE, MqConstant.ORDER_SUBMIT_ROUTING_KEY, command);
    }

    /** 秒杀桥接事务只保存命令；后台独立发布，不阻塞秒杀 HTTP 或桥接事务。 */
    public void seckillDeferred(com.sky.dto.SeckillOrderMessageDTO command) {
        MqFailMessage pending=MqFailMessage.builder().exchangeName(MqConstant.ORDER_EXCHANGE)
            .routingKey(SeckillRouting.route(command)).messageBody(JSON.toJSONString(command))
            .failReason("pending publish").status(0).retryCount(0).createTime(LocalDateTime.now()).build();
        failures.insert(pending);
    }

    /** Batch outbox insert shares the bridge transaction; no network publication here. */
    public void seckillDeferredBatch(java.util.List<com.sky.dto.SeckillOrderMessageDTO> commands) {
        if(commands.isEmpty()) return;
        java.time.LocalDateTime now=java.time.LocalDateTime.now();
        var rows=commands.stream().map(command -> MqFailMessage.builder().exchangeName(MqConstant.ORDER_EXCHANGE)
            .routingKey(SeckillRouting.route(command)).messageBody(JSON.toJSONString(command))
            .failReason("pending publish").status(0).retryCount(0).createTime(now).build()).toList();
        failures.insertBatch(rows);
    }

    /** Due-time outbox avoids blocking the consumer and avoids restarting a queue TTL. */
    public void timeoutDeferred(String orderNumber, java.time.LocalDateTime deadline) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || deadline==null)
            throw new IllegalStateException("Seckill timeout requires the order transaction and deadline");
        failures.insert(MqFailMessage.builder().exchangeName(MqConstant.ORDER_DELAY_EXCHANGE)
            .routingKey(MqConstant.SECKILL_TIMEOUT_ROUTING_KEY).messageBody(JSON.toJSONString(orderNumber))
            .failReason("scheduled timeout").status(0).retryCount(0).createTime(LocalDateTime.now())
            .availableAt(LocalDateTime.now()).deliveryDeadline(deadline).build());
    }

    public void timeoutDeferredBatch(java.util.List<com.sky.entity.Orders> orders) {
        if(orders.isEmpty())return;
        if(!TransactionSynchronizationManager.isActualTransactionActive() ||
                orders.stream().anyMatch(order->order.getExpireTime()==null))
            throw new IllegalStateException("Seckill timeout batch requires transaction and deadlines");
        var now=LocalDateTime.now();
        var rows=orders.stream().map(order->MqFailMessage.builder().exchangeName(MqConstant.ORDER_DELAY_EXCHANGE)
                .routingKey(MqConstant.SECKILL_TIMEOUT_ROUTING_KEY).messageBody(JSON.toJSONString(order.getNumber()))
                .failReason("scheduled timeout").status(0).retryCount(0).createTime(now)
                .availableAt(now).deliveryDeadline(order.getExpireTime()).build()).toList();
        failures.insertBatch(rows);
    }

    /** The delay queue has a 15-minute TTL and routes expiry to its DLQ. */
    public void timeoutAfterCommit(String orderNumber) {
        enqueue(MqConstant.ORDER_DELAY_EXCHANGE, MqConstant.ORDER_DELAY_ROUTING_KEY, orderNumber);
    }

    /** mq_fail_message 同时承担待发消息表职责，并非只有发送异常才写入。 */
    private void enqueue(String exchange, String routingKey, Object payload) {
        MqFailMessage pending = MqFailMessage.builder()
                .exchangeName(exchange).routingKey(routingKey)
                .messageBody(JSON.toJSONString(payload)).failReason("pending publish")
                .status(0).retryCount(0).createTime(LocalDateTime.now()).build();
        failures.insert(pending);
        // Background retry publisher owns publication; never perform network I/O in a commit callback.
    }

    /** Broker ACK 只证明消息被接收，不证明消费者已成功创建订单。 */
    private void publish(MqFailMessage pending) {
        long profileStarted = System.nanoTime();
        try {
            CorrelationData correlation = new CorrelationData("command:" + pending.getId());
            long sendStarted = System.nanoTime();
            try {
                rabbit.convertAndSend(pending.getExchangeName(), pending.getRoutingKey(), payloadFor(pending), message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setMessageId(String.valueOf(pending.getId()));
                        return message;
                    }, correlation);
            } finally { SeckillTiming.record("timeout_send", System.nanoTime()-sendStarted); }
            long confirmStarted = System.nanoTime();
            CorrelationData.Confirm confirm;
            try { confirm = correlation.getFuture().get(5, TimeUnit.SECONDS); }
            finally { SeckillTiming.record("timeout_confirm_wait", System.nanoTime()-confirmStarted); }
            // mandatory return 表示无队列可路由；即使收到 ACK 也不能当发送成功。
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException("broker did not confirm/rout command");
            }
            SeckillTiming.measure("timeout_mark_published", () -> failures.markPublished(pending.getId(), LocalDateTime.now()));
        } catch (Exception ex) {
            // The durable row remains retryable.  Do not throw from afterCommit:
            // the HTTP request has already been accepted successfully.
            log.warn("Order command publish deferred, id={}", pending.getId(), ex);
        } finally {
            SeckillTiming.record("timeout_publish", System.nanoTime()-profileStarted);
        }
    }

    private Object payloadFor(MqFailMessage pending) {
        if (MqConstant.ORDER_SUBMIT_ROUTING_KEY.equals(pending.getRoutingKey())) {
            return JSON.parseObject(pending.getMessageBody(), OrderSubmitMessageDTO.class);
        }
        if (SeckillRouting.isOrder(pending.getRoutingKey())) {
            return JSON.parseObject(pending.getMessageBody(), com.sky.dto.SeckillOrderMessageDTO.class);
        }
        if (MqConstant.ORDER_DELAY_ROUTING_KEY.equals(pending.getRoutingKey())) {
            return JSON.parseObject(pending.getMessageBody(), String.class);
        }
        return pending.getMessageBody();
    }
}
