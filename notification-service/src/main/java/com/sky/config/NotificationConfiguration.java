package com.sky.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

@Configuration
public class NotificationConfiguration {
    public static final String EXCHANGE="sky.notification.events", QUEUE="sky.notification.order", DLX="sky.notification.dlx", DLQ="sky.notification.dead";
    @Bean DirectExchange notificationExchange(){ return new DirectExchange(EXCHANGE,true,false); }
    @Bean DirectExchange notificationDlx(){ return new DirectExchange(DLX,true,false); }
    @Bean Queue notificationQueue(){ return QueueBuilder.durable(QUEUE).deadLetterExchange(DLX).deadLetterRoutingKey("dead").build(); }
    @Bean Queue notificationDeadQueue(){ return QueueBuilder.durable(DLQ).build(); }
    @Bean Binding notificationBinding(){ return BindingBuilder.bind(notificationQueue()).to(notificationExchange()).with("notification.order"); }
    @Bean Binding notificationDeadBinding(){ return BindingBuilder.bind(notificationDeadQueue()).to(notificationDlx()).with("dead"); }
    @Bean ServerEndpointExporter serverEndpointExporter(){ return new ServerEndpointExporter(); }
}
