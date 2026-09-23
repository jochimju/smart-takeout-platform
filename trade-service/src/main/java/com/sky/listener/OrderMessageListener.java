package com.sky.listener;

import com.rabbitmq.client.Channel;
import com.sky.constant.MqConstant;
import com.sky.dto.OrderSubmitMessageDTO;
import com.sky.mapper.MqFailMessageMapper;
import com.sky.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Consumes order-related messages and acknowledges them only after the
 * corresponding database transaction succeeds.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderMessageListener {

    private final OrderService orderService;
    private final com.sky.service.impl.TimeoutFailureService timeoutFailures;
    private final MqFailMessageMapper failures;

    @RabbitListener(queues = MqConstant.ORDER_SUBMIT_QUEUE)
    public void handleOrderSubmit(OrderSubmitMessageDTO messageDTO, Channel channel, Message message) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            orderService.createOrderFromMessage(messageDTO);
            channel.basicAck(deliveryTag, false);
        } catch (Exception failure) {
            log.error("Failed to process order submission message for order {}", messageDTO.getOrderNumber(), failure);
            persistSubmissionFailure(message, messageDTO.getOrderNumber(), failure);
            try {
                // A dead-lettered command cannot complete later. Releasing the
                // request id allows the client to submit a fresh command.
                orderService.releaseFailedSubmission(messageDTO.getOrderNumber());
            } catch (Exception releaseFailure) {
                log.error("Could not release failed submission {}", messageDTO.getOrderNumber(), releaseFailure);
            }
            channel.basicNack(deliveryTag, false, false);
        }
    }

    @RabbitListener(queues = MqConstant.ORDER_DEAD_QUEUE)
    public void handleTimeoutOrder(String orderNumber, Channel channel, Message message) throws IOException {
        long tag=message.getMessageProperties().getDeliveryTag();
        try {
            orderService.cancelTimeoutOrder(orderNumber);
        } catch(Exception failure) {
            log.error("Timeout cancellation failed for {}",orderNumber,failure);
            try {
                timeoutFailures.record(orderNumber,failure);
            } catch(Exception persistenceFailure) {
                // Database unavailable: keep the broker copy as well as the original outbox event.
                channel.basicNack(tag,false,true);
                return;
            }
        }
        // Business commit or durable recovery commit must precede the acknowledgment.
        // An ACK failure propagates to the container, never rewrites completed job state.
        channel.basicAck(tag,false);
    }

    private void acknowledgeAfterSuccess(Channel channel, Message message, Runnable action, String actionName, String orderNumber)
            throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            action.run();
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Failed to process " + actionName + " message for order " + orderNumber, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private void persistSubmissionFailure(Message message, String orderNumber, Exception failure) {
        String messageId = message.getMessageProperties().getMessageId();
        if (messageId == null) {
            return;
        }
        try {
            String detail = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
            if (detail.length() > 500) {
                detail = detail.substring(0, 500);
            }
            failures.markConsumerFailure(Long.valueOf(messageId), detail, LocalDateTime.now());
        } catch (Exception persistenceFailure) {
            log.error("Could not persist failed submission diagnostic for order {}", orderNumber, persistenceFailure);
        }
    }
}
