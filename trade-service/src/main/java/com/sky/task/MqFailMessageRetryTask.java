package com.sky.task;

import com.alibaba.fastjson.JSON;
import com.sky.constant.MqConstant;
import com.sky.dto.OrderSubmitMessageDTO;
import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.entity.MqFailMessage;
import com.sky.mapper.MqFailMessageMapper;
import com.sky.observability.SeckillTiming;
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
    @Autowired
    private com.sky.service.mq.SeckillPublishStore seckillPublishStore;
    private record PendingConfirmation(com.sky.service.mq.SeckillPublishStore.Result result,long enqueuedAt) {}
    private final java.util.concurrent.ConcurrentLinkedQueue<PendingConfirmation> confirmations=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger inFlight=new java.util.concurrent.atomic.AtomicInteger();
    @org.springframework.beans.factory.annotation.Value("${sky.seckill.publisher-workers:2}") private int publisherWorkers=2;

    /** 每轮先释放过期发送租约，再竞争领取待发消息；这是发送补偿，不是建单成功检查。 */
    @Scheduled(fixedDelayString="${sky.order.lease-recovery-ms:1000}",scheduler="normalPublisherScheduler")
    public void retryFailedMessages() {
        seckillPublishStore.releaseExpiredClaims();
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

    /** 秒杀独立快速发布；异步等待确认，不改变普通订单原有发送流程。 */
    @Scheduled(fixedDelayString="${sky.seckill.publish-ms:100}", scheduler="seckillScheduler")
    public void publishSeckillPending() {
        publishClaimed(false);
    }
    @Scheduled(fixedDelayString="${sky.seckill.publish-ms:100}",initialDelay=50,scheduler="seckillScheduler")
    public void publishSeckillPendingSecond() {if(publisherWorkers>=2)publishClaimed(false);}

    /** Scheduled timeout events are published at their actual deadline; outages remain retryable. */
    @Scheduled(fixedDelayString="${sky.seckill.timeout-publish-ms:100}", scheduler="seckillScheduler")
    public void publishSeckillTimeouts() {
        seckillPublishStore.recoverTimeouts();
        publishClaimed(true);
    }

    private void publishClaimed(boolean timeout) {
        SeckillTiming.root(timeout?"publish_timeout":"publish_create",()-> {
            long waiting=System.nanoTime();
            // Same monitor and critical section as before; expose contention without changing coordination.
            int reserved;
            while(true) {
                int current=inFlight.get();reserved=Math.min(200,1000-current);
                if(reserved<=0){SeckillTiming.count("inflight_full",1);return;}
                if(inFlight.compareAndSet(current,current+reserved))break;
            }
            final int limit=reserved;
            java.util.List<MqFailMessage> rows;
            try { rows=SeckillTiming.rootValue(timeout?"claim_timeout":"claim_create",()->seckillPublishStore.claim(timeout,limit)); }
            catch(RuntimeException failure){inFlight.addAndGet(-reserved);throw failure;}
            inFlight.addAndGet(rows.size()-reserved);
                SeckillTiming.count("claimed",rows.size());
                SeckillTiming.run("send_loop",()-> {for(var row:rows)publishAsync(row,timeout);});
        });
    }

    /** Broker callback never performs JDBC; confirms are durably settled in short batch transactions. */
    @Scheduled(fixedDelayString="${sky.seckill.confirm-flush-ms:50}", scheduler="seckillScheduler")
    public void flushSeckillConfirmations() {
        SeckillTiming.root("publish_flush",()-> {
            long waiting=System.nanoTime();
            synchronized(this) {
                SeckillTiming.record("monitor_wait",System.nanoTime()-waiting);
                var pending=new java.util.ArrayList<PendingConfirmation>();
                while(pending.size()<200) {var row=confirmations.poll();if(row==null)break;pending.add(row);}
                if(pending.isEmpty())return;
                for(var row:pending) SeckillTiming.root("confirm_queue",()->SeckillTiming.record("wait",System.nanoTime()-row.enqueuedAt()));
                var rows=pending.stream().map(PendingConfirmation::result).toList();
                try {
                    SeckillTiming.root("publish_complete",()->seckillPublishStore.complete(rows));
                    inFlight.addAndGet(-rows.size());SeckillTiming.count("settled",rows.size());
                }
                catch(RuntimeException failure) {confirmations.addAll(pending);log.warn("Seckill batch confirms retained for retry",failure);}
            }
        });
    }

    private void publishAsync(MqFailMessage pending, boolean timeout) {
        long started=System.nanoTime();
        try {
            CorrelationData correlation=new CorrelationData("seckill:"+pending.getId());
            Object payload=SeckillTiming.measure("payload_decode",()->parsePayload(pending));
            long remaining=timeout && pending.getDeliveryDeadline()!=null?java.time.Duration.between(LocalDateTime.now(),pending.getDeliveryDeadline()).toMillis():0;
            String routing=timeout?com.sky.service.mq.TimeoutRouting.route(remaining):pending.getRoutingKey();
            SeckillTiming.run("convert_send",()->rabbitTemplate.convertAndSend(pending.getExchangeName(),routing,payload,outgoing -> {
                outgoing.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                outgoing.getMessageProperties().setMessageId(String.valueOf(pending.getId()));
                return outgoing;
            },correlation));
            correlation.getFuture().orTimeout(5,TimeUnit.SECONDS).whenComplete((confirm,error) -> {
                boolean acknowledged=error==null && confirm!=null && confirm.isAck() && correlation.getReturned()==null;
                SeckillTiming.root("broker_confirm",()-> {
                    SeckillTiming.record("send_to_callback",System.nanoTime()-started);
                    SeckillTiming.count(acknowledged?"ack":"unconfirmed",1);
                });
                confirmations.add(new PendingConfirmation(new com.sky.service.mq.SeckillPublishStore.Result(pending.getId(),pending.getClaimToken(),timeout,acknowledged),System.nanoTime()));
            });
        } catch(Exception failure) { confirmations.add(new PendingConfirmation(new com.sky.service.mq.SeckillPublishStore.Result(pending.getId(),pending.getClaimToken(),timeout,false),System.nanoTime())); }
    }

    /** 最多重试 5 次发送失败；Broker 确认成功后停止，消费业务失败另行记录。 */
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
        if (com.sky.service.mq.SeckillRouting.isOrder(routingKey)) {
            return JSON.parseObject(message.getMessageBody(), SeckillOrderMessageDTO.class);
        }
        if (MqConstant.SECKILL_TIMEOUT_ROUTING_KEY.equals(routingKey)
                || MqConstant.ORDER_DEAD_ROUTING_KEY.equals(routingKey)
                || MqConstant.ORDER_DELAY_ROUTING_KEY.equals(routingKey)) {
            return JSON.parseObject(message.getMessageBody(), String.class);
        }
        return message.getMessageBody();
    }
}
