package com.sky.task;
import com.sky.service.mq.SeckillPublishStore;
import com.sky.dto.OrderSubmitMessageDTO;
import com.sky.entity.MqFailMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.MessageDeliveryMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
@Component @RequiredArgsConstructor @Slf4j
public class NormalOrderPublisherTask {
 @org.springframework.beans.factory.annotation.Value("${sky.order.publisher-enabled:true}") private boolean enabled=true;
 private final SeckillPublishStore store;private final RabbitTemplate rabbit;
 private final AtomicInteger inFlight=new AtomicInteger();private final ConcurrentLinkedQueue<SeckillPublishStore.Result> confirmations=new ConcurrentLinkedQueue<>();
 @Scheduled(fixedDelayString="${sky.order.publish-ms:100}",scheduler="normalPublisherScheduler")
 public void publish() {
  if(!enabled)return;
  int current,reserved;do{current=inFlight.get();reserved=Math.min(100,200-current);if(reserved<=0)return;}while(!inFlight.compareAndSet(current,current+reserved));
  List<MqFailMessage> rows;
  try{rows=store.claimNormal(reserved);}catch(RuntimeException failure){inFlight.addAndGet(-reserved);throw failure;}
  inFlight.addAndGet(rows.size()-reserved);
  for(var row:rows) {
   var correlation=new CorrelationData("normal:"+row.getId()+":"+row.getClaimToken());
   try {
    rabbit.convertAndSend(row.getExchangeName(),row.getRoutingKey(),com.alibaba.fastjson.JSON.parseObject(row.getMessageBody(),OrderSubmitMessageDTO.class),message->{message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);message.getMessageProperties().setMessageId(row.getId().toString());return message;},correlation);
    correlation.getFuture().orTimeout(5,TimeUnit.SECONDS).whenComplete((confirm,error)->confirmations.add(new SeckillPublishStore.Result(row.getId(),row.getClaimToken(),false,error==null && confirm!=null && confirm.isAck() && correlation.getReturned()==null)));
   } catch(Exception failure){confirmations.add(new SeckillPublishStore.Result(row.getId(),row.getClaimToken(),false,false));}
  }
 }
 @Scheduled(fixedDelayString="${sky.order.confirm-flush-ms:50}",scheduler="normalPublisherScheduler")
 public synchronized void settle() {
  var results=new ArrayList<SeckillPublishStore.Result>();while(results.size()<100){var row=confirmations.poll();if(row==null)break;results.add(row);}if(results.isEmpty())return;
  try{store.complete(results);inFlight.addAndGet(-results.size());}catch(RuntimeException failure){confirmations.addAll(results);log.warn("Normal publisher confirmations retained",failure);}
 }
}
