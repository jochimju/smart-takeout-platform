package com.sky.observability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class OrderCreationMetricsTest {
 @Test void rollbackNeverCountsAnOrderAndCommitRecordsActualAcceptanceWait() {
  var meters=new SimpleMeterRegistry();var metrics=new OrderCreationMetrics(meters);
  TransactionSynchronizationManager.initSynchronization();
  try{metrics.committed("normal",List.of(System.currentTimeMillis()-100));assertEquals(0,meters.get("order.creation.wait").tag("mode","normal").timer().count());
   for(var hook:TransactionSynchronizationManager.getSynchronizations())hook.afterCompletion(1);
   assertEquals(0,meters.get("order.creation.wait").tag("mode","normal").timer().count());
  }finally{TransactionSynchronizationManager.clearSynchronization();}
  TransactionSynchronizationManager.initSynchronization();
  try{metrics.committed("normal",List.of(System.currentTimeMillis()-100));for(var hook:TransactionSynchronizationManager.getSynchronizations())hook.afterCommit();
   assertEquals(1,meters.get("order.creation.wait").tag("mode","normal").timer().count());
  }finally{TransactionSynchronizationManager.clearSynchronization();}
 }
}
