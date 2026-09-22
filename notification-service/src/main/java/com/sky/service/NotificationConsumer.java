package com.sky.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.config.NotificationConfiguration;
import com.sky.contract.notification.BusinessEvent;
import com.sky.websocket.WebSocketServer;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class NotificationConsumer {
    private static final String CHANNEL="sky:orders:websocket";
    private final StringRedisTemplate redis;
    private final WebSocketServer sockets;

    @RabbitListener(queues = NotificationConfiguration.QUEUE)
    public void consume(String body) {
        String dedupeKey=null;
        boolean claimed=false;
        try {
            String normalized=normalize(body);
            BusinessEvent event=JSON.parseObject(normalized,BusinessEvent.class);
            if(event==null || event.getEventId()==null || event.getEventId().isBlank()) throw new IllegalArgumentException("event id missing");
            dedupeKey="notification:event:"+event.getEventId();
            Boolean first=redis.opsForValue().setIfAbsent(dedupeKey,"1", Duration.ofDays(7));
            if(!Boolean.TRUE.equals(first)) return;
            claimed=true;
            JSONObject payload=JSON.parseObject(event.getPayload());
            redis.convertAndSend(CHANNEL,payload.toJSONString());
        } catch (RuntimeException failure) {
            // Do not turn a transient publish failure into a permanent duplicate marker.
            if(claimed && dedupeKey!=null) redis.delete(dedupeKey);
            redis.opsForHash().put("notification:failures", Integer.toHexString(body.hashCode()), body);
            throw failure;
        }
    }

    public java.util.Map<Object,Object> failures(){ return redis.opsForHash().entries("notification:failures"); }
    public void replay(String id){
        Object body=redis.opsForHash().get("notification:failures",id);
        if(body==null) throw new IllegalArgumentException("failure not found");
        BusinessEvent event=JSON.parseObject(normalize(String.valueOf(body)),BusinessEvent.class);
        redis.delete("notification:event:"+event.getEventId());
        consume(String.valueOf(body));
        redis.opsForHash().delete("notification:failures",id);
    }
    private String normalize(String body){
        return body != null && body.startsWith("\"") ? JSON.parseObject(body,String.class) : body;
    }

    @Bean
    RedisMessageListenerContainer websocketListener(RedisConnectionFactory factory){
        RedisMessageListenerContainer container=new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener((message,pattern)->sockets.broadcast(new String(message.getBody(), StandardCharsets.UTF_8)),new ChannelTopic(CHANNEL));
        return container;
    }
}
