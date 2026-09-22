package com.sky.task;

import com.alibaba.fastjson.JSON;
import com.sky.constant.MqConstant;
import com.sky.dto.OrderSubmitMessageDTO;
import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.entity.MqFailMessage;
import com.sky.mapper.MqFailMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class MqFailMessageRetryTask {

    private static final int MAX_RETRY = 5;
    private static final int BATCH_SIZE = 50;

    @Autowired
    private MqFailMessageMapper mqFailMessageMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelay = 60000)
    public void retryFailedMessages() {
        mqFailMessageMapper.releaseExpiredClaims();
        List<MqFailMessage> messages = mqFailMessageMapper.listRetryable(MAX_RETRY, BATCH_SIZE);
        if (messages == null || messages.size() == 0) {
            return;
        }
        for (MqFailMessage message : messages) {
            if (mqFailMessageMapper.claimRetry(message.getId(), MAX_RETRY) == 1) {
                retryOne(message);
            }
        }
    }

    private void retryOne(MqFailMessage message) {
        try {
            Object payload = parsePayload(message);
            CorrelationData correlation = new CorrelationData("retry:" + message.getId());
            rabbitTemplate.convertAndSend(message.getExchangeName(), message.getRoutingKey(), payload, outgoing -> {
                outgoing.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                outgoing.getMessageProperties().setMessageId(String.valueOf(message.getId()));
                return outgoing;
            }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException("broker did not confirm/rout retry message");
            }
            mqFailMessageMapper.markSuccess(message.getId(), LocalDateTime.now());
        } catch (Exception e) {
            log.warn("retry mq fail message failed, id: {}", message.getId(), e);
            String reason = e.getMessage();
            if (!StringUtils.hasText(reason)) {
                reason = e.getClass().getSimpleName();
            }
            int retryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
            if (retryCount + 1 >= MAX_RETRY) {
                mqFailMessageMapper.markDead(message.getId(), reason, LocalDateTime.now());
            } else {
                mqFailMessageMapper.increaseRetry(message.getId(), reason, LocalDateTime.now());
            }
        }
    }

    private Object parsePayload(MqFailMessage message) {
        String routingKey = message.getRoutingKey();
        if (MqConstant.ORDER_SUBMIT_ROUTING_KEY.equals(routingKey)) {
            return JSON.parseObject(message.getMessageBody(), OrderSubmitMessageDTO.class);
        }
        if (MqConstant.SECKILL_ORDER_ROUTING_KEY.equals(routingKey)) {
            return JSON.parseObject(message.getMessageBody(), SeckillOrderMessageDTO.class);
        }
        if (MqConstant.ORDER_DEAD_ROUTING_KEY.equals(routingKey)
                || MqConstant.ORDER_DELAY_ROUTING_KEY.equals(routingKey)) {
            return JSON.parseObject(message.getMessageBody(), String.class);
        }
        return message.getMessageBody();
    }
}
