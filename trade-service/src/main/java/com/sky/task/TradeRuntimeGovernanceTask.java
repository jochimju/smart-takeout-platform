package com.sky.task;
import com.sky.service.impl.TradeRuntimeOperations;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component @Slf4j
public class TradeRuntimeGovernanceTask {
 private final TradeRuntimeOperations operations;
 private final ConcurrentHashMap<String,AtomicLong> values=new ConcurrentHashMap<>();
 private final AtomicLong available=new AtomicLong(1);private long lastWarning;
 public TradeRuntimeGovernanceTask(TradeRuntimeOperations operations,MeterRegistry meters) {
  this.operations=operations;meters.gauge("trade.runtime.scan.available",available);
  for(String kind:java.util.List.of("publishFailed","expiredLeases","acceptedOverdue","cacheCompensationStale","reliabilityFailed","paymentRecoveryFailed","unretiredActivities")) {
   var value=new AtomicLong();values.put(kind,value);meters.gauge("trade.runtime.backlog",java.util.List.of(io.micrometer.core.instrument.Tag.of("kind",kind)),value);
  }
 }
 @Scheduled(fixedDelayString="${sky.runtime.alert-ms:30000}",scheduler="normalPublisherScheduler")
 public void inspect() {
  try {
   var snapshot=operations.backlog();snapshot.forEach((key,value)->values.get(key).set(value));available.set(1);
   boolean unhealthy=snapshot.entrySet().stream().anyMatch(e->!e.getKey().equals("unretiredActivities")&&e.getValue()>0);
   if(unhealthy&&System.currentTimeMillis()-lastWarning>=60000){lastWarning=System.currentTimeMillis();log.warn("Trade runtime backlog requires recovery: {}",snapshot);}
  } catch(RuntimeException error){available.set(0);log.warn("Trade runtime health scan unavailable",error);}
 }
 @Scheduled(fixedDelayString="${sky.runtime.archive-ms:3600000}",initialDelayString="${sky.runtime.archive-ms:3600000}",scheduler="normalPublisherScheduler")
 public void archive(){try{operations.archive(30);}catch(RuntimeException error){log.warn("Trade archive retained for retry",error);}}
}
