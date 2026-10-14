package com.sky.config;

import com.sky.constant.MqConstant;
import com.sky.json.JacksonObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class RabbitMqConfiguration {
    @Bean public org.springframework.amqp.core.Declarables timeoutLanes() {
        return com.sky.service.mq.TimeoutRouting.declarations(orderDelayExchange());
    }

    @Bean
    public MessageConverter messageConverter() {
        // Reuse the application's date/time-aware mapper so order messages
        // containing LocalDateTime (for example estimatedDeliveryTime) can be
        // serialized and deserialized by RabbitMQ.
        return new Jackson2JsonMessageConverter(new JacksonObjectMapper());
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        rabbitTemplate.setMandatory(true);
        // This template is defined manually, so enable Micrometer observation here
        // instead of relying on Spring Boot's RabbitTemplate auto-configuration.
        rabbitTemplate.setObservationEnabled(true);
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("rabbitmq message publish failed, correlationData: {}, cause: {}", correlationData, cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> log.error(
                "rabbitmq message returned, exchange: {}, routingKey: {}, replyCode: {}, replyText: {}, body: {}",
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyCode(),
                returned.getReplyText(),
                new String(returned.getMessage().getBody())));
        return rabbitTemplate;
    }

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(MqConstant.ORDER_EXCHANGE, true, false);
    }
    /** Producers declare their exchange; notification-service owns its queues and consumers. */
    @Bean public DirectExchange notificationEventExchange() {
        return new DirectExchange(com.sky.service.impl.BusinessEventOutbox.EXCHANGE,true,false);
    }

    @Bean
    public Queue orderSubmitQueue() {
        return QueueBuilder.durable(MqConstant.ORDER_SUBMIT_QUEUE)
                .deadLetterExchange(MqConstant.ORDER_FAILURE_EXCHANGE)
                .deadLetterRoutingKey(MqConstant.ORDER_FAILURE_ROUTING_KEY).build();
    }

    @Bean
    public Binding orderSubmitBinding() {
        return BindingBuilder.bind(orderSubmitQueue()).to(orderExchange()).with(MqConstant.ORDER_SUBMIT_ROUTING_KEY);
    }

    @Bean
    public org.springframework.amqp.core.Declarables seckillBucketQueues() {
        var declarations=new java.util.ArrayList<org.springframework.amqp.core.Declarable>();
        for(int bucket=0;bucket<8;bucket++) {
            var queue=QueueBuilder.durable("sky.seckill.order.bucket."+bucket+".v3.queue")
                .deadLetterExchange(MqConstant.ORDER_FAILURE_EXCHANGE)
                .deadLetterRoutingKey(MqConstant.ORDER_FAILURE_ROUTING_KEY).build();
            declarations.add(queue);
            declarations.add(BindingBuilder.bind(queue).to(orderExchange()).with("seckill.order.bucket."+bucket));
        }
        return new org.springframework.amqp.core.Declarables(declarations);
    }

    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(MqConstant.SECKILL_ORDER_QUEUE)
                .deadLetterExchange(MqConstant.ORDER_FAILURE_EXCHANGE)
                .deadLetterRoutingKey(MqConstant.ORDER_FAILURE_ROUTING_KEY).build();
    }

    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder.bind(seckillOrderQueue()).to(orderExchange()).with(MqConstant.SECKILL_ORDER_ROUTING_KEY);
    }

    @Bean
    public DirectExchange orderFailureExchange() {
        return new DirectExchange(MqConstant.ORDER_FAILURE_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderFailureQueue() {
        return QueueBuilder.durable(MqConstant.ORDER_FAILURE_QUEUE).build();
    }

    @Bean
    public Binding orderFailureBinding() {
        return BindingBuilder.bind(orderFailureQueue()).to(orderFailureExchange()).with(MqConstant.ORDER_FAILURE_ROUTING_KEY);
    }

    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange(MqConstant.ORDER_DELAY_EXCHANGE, true, false);
    }

    /** 无主动消费者的延迟队列：15 分钟 TTL 到期后，通过死信路由进入关闭队列。 */
    @Bean
    public Queue orderDelayQueue() {
        return QueueBuilder.durable(MqConstant.ORDER_DELAY_QUEUE)
                .ttl((int) MqConstant.ORDER_TIMEOUT_MILLIS)
                .deadLetterExchange(MqConstant.ORDER_DELAY_EXCHANGE)
                .deadLetterRoutingKey(MqConstant.ORDER_DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding orderDelayBinding() {
        return BindingBuilder.bind(orderDelayQueue()).to(orderDelayExchange()).with(MqConstant.ORDER_DELAY_ROUTING_KEY);
    }

    /** 此死信队列是正常超时处理入口，不表示建单业务失败。 */
    @Bean
    public Queue orderDeadQueue() {
        return QueueBuilder.durable(MqConstant.ORDER_DEAD_QUEUE).build();
    }

    /** Seckill scheduled outbox publishes only when its real deadline is due. */
    @Bean
    public Binding seckillTimeoutBinding() {
        return BindingBuilder.bind(orderDeadQueue()).to(orderDelayExchange()).with(MqConstant.SECKILL_TIMEOUT_ROUTING_KEY);
    }

    @Bean
    public Binding orderDeadBinding() {
        return BindingBuilder.bind(orderDeadQueue()).to(orderDelayExchange()).with(MqConstant.ORDER_DEAD_ROUTING_KEY);
    }
}
