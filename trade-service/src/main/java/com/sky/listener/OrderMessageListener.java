package com.sky.listener;

import com.rabbitmq.client.Channel;
import com.sky.constant.MqConstant;
import com.sky.dto.OrderSubmitMessageDTO;
import com.sky.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

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

    @RabbitListener(queues = MqConstant.ORDER_SUBMIT_QUEUE)
    public void handleOrderSubmit(OrderSubmitMessageDTO messageDTO, Channel channel, Message message) throws IOException {
        acknowledgeAfterSuccess(channel, message, () -> orderService.createOrderFromMessage(messageDTO),
                "order submission", messageDTO.getOrderNumber());
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
}
