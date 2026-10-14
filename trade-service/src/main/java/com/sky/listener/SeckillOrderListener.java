package com.sky.listener;

import com.rabbitmq.client.Channel;
import com.sky.constant.MqConstant;
import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.exception.OrderBusinessException;
import com.sky.service.SeckillService;
import com.sky.service.impl.SeckillAsyncStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillOrderListener {
    private final SeckillService service;
    private final SeckillAsyncStore store;
    @org.springframework.beans.factory.annotation.Autowired private com.sky.service.impl.SeckillBatchCoordinator batches;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.amqp.support.converter.MessageConverter converter;

    @RabbitListener(queues=MqConstant.SECKILL_ORDER_QUEUE, containerFactory="seckillListenerFactory",autoStartup="#{!${sky.seckill.batch-enabled:true}}")
    public void handle(SeckillOrderMessageDTO command,Channel channel,Message message) throws IOException {
        long tag=message.getMessageProperties().getDeliveryTag();
        try {
            com.sky.observability.SeckillTiming.root("consume", () -> service.createSeckillOrder(command));
        } catch(SeckillAsyncStore.MissingAcceptance missing) {
            log.error("No durable acceptance for seckill command; retain in failure DLQ",missing);
            channel.basicNack(tag,false,false);
            return;
        } catch(Exception failure) {
            boolean permanent=com.sky.service.impl.OrderFailurePolicy.permanent(failure);
            try { store.failed(command.getOrderNumber(),failure,permanent); }
            catch(Exception persistenceFailure) {
                log.error("Cannot persist seckill retry; retain broker copy: {}",command.getOrderNumber(),persistenceFailure);
                channel.basicNack(tag,false,true);
                return;
            }
        }
        // ACK failures must cause redelivery, never turn a committed order into a failure.
        channel.basicAck(tag,false);
    }

    @RabbitListener(queues="sky.seckill.order.bucket.0.v3.queue",containerFactory="seckillBucketListenerFactory")
    @RabbitListener(queues="sky.seckill.order.bucket.1.v3.queue",containerFactory="seckillBucketListenerFactory")
    @RabbitListener(queues="sky.seckill.order.bucket.2.v3.queue",containerFactory="seckillBucketListenerFactory")
    @RabbitListener(queues="sky.seckill.order.bucket.3.v3.queue",containerFactory="seckillBucketListenerFactory")
    @RabbitListener(queues="sky.seckill.order.bucket.4.v3.queue",containerFactory="seckillBucketListenerFactory")
    @RabbitListener(queues="sky.seckill.order.bucket.5.v3.queue",containerFactory="seckillBucketListenerFactory")
    @RabbitListener(queues="sky.seckill.order.bucket.6.v3.queue",containerFactory="seckillBucketListenerFactory")
    @RabbitListener(queues="sky.seckill.order.bucket.7.v3.queue",containerFactory="seckillBucketListenerFactory")
    public void handleBucketBatch(java.util.List<Message> messages,Channel channel)throws IOException {
        handleBatch(messages,channel);
    }

    @RabbitListener(queues=MqConstant.SECKILL_ORDER_QUEUE,containerFactory="seckillBatchListenerFactory",autoStartup="${sky.seckill.batch-enabled:true}")
    public void handleBatch(java.util.List<Message> messages,Channel channel)throws IOException {
        record Delivery(Message message,SeckillOrderMessageDTO command) {}
        var deliveries=new java.util.ArrayList<Delivery>();
        for(var message:messages) {
            try {
                var command=(SeckillOrderMessageDTO)converter.fromMessage(message);
                if(command==null || command.getOrderNumber()==null)throw new IllegalArgumentException("Missing command identity");
                deliveries.add(new Delivery(message,command));
            }
            catch(Exception malformed) {channel.basicNack(message.getMessageProperties().getDeliveryTag(),false,false);}
        }
        var failures=batches.fulfill(deliveries.stream().map(Delivery::command).toList());
        for(var delivery:deliveries) {
            var command=delivery.command();long tag=delivery.message().getMessageProperties().getDeliveryTag();
            var failure=failures.get(command.getOrderNumber());
            if(failure instanceof SeckillAsyncStore.MissingAcceptance) {channel.basicNack(tag,false,false);continue;}
            if(failure!=null) {
                boolean permanent=com.sky.service.impl.OrderFailurePolicy.permanent(failure);
                try {store.failed(command.getOrderNumber(),failure,permanent);}
                catch(Exception persistenceFailure) {channel.basicNack(tag,false,true);continue;}
            }
            // Individual ACKs only after the service proxy has committed; ACK failure never compensates a committed order.
            channel.basicAck(tag,false);
        }
    }
}
