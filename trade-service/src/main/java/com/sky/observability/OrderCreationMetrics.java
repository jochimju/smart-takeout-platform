package com.sky.observability;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** End-to-end acceptance-to-commit latency, recorded only after successful commit. */
@Component
public class OrderCreationMetrics {
 private final Map<String,Timer> timers;
 public OrderCreationMetrics(MeterRegistry registry) {
  timers=Map.of("normal",Timer.builder("order.creation.wait").tag("mode","normal").publishPercentiles(.5,.95,.99).register(registry),
   "seckill",Timer.builder("order.creation.wait").tag("mode","seckill").publishPercentiles(.5,.95,.99).register(registry));
 }
 public void committed(String mode,List<Long> acceptedAt) {
  if(!TransactionSynchronizationManager.isSynchronizationActive())return;
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
   @Override public void afterCommit(){long now=System.currentTimeMillis();for(Long accepted:acceptedAt)if(accepted!=null&&accepted<=now)
    try{timers.get(mode).record(now-accepted,TimeUnit.MILLISECONDS);}catch(RuntimeException ignored){/* metrics cannot fail an order */}}
  });
 }
}
