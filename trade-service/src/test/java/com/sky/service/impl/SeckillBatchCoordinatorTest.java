package com.sky.service.impl;

import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.exception.OrderBusinessException;
import com.sky.service.PreparedSeckillOrder;
import com.sky.service.SeckillService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeckillBatchCoordinatorTest {
    @Test void deterministicBadRowIsSplitAndGoodRowsRemainSuccessful() {
        var service=mock(SeckillService.class);var commands=commands(service);
        doAnswer(call->{List<PreparedSeckillOrder> rows=call.getArgument(0);if(rows.stream().anyMatch(row->"bad".equals(row.command().getOrderNumber())))throw new OrderBusinessException("红包不可用");return null;}).when(service).createPreparedOrders(anyList());
        var errors=new SeckillBatchCoordinator(service).fulfill(commands);
        assertEquals(Set.of("bad"),errors.keySet());verify(service).createPreparedOrders(argThat(rows->rows.size()==1 && "good".equals(rows.get(0).command().getOrderNumber())));
    }
    @Test void databaseOutageDoesNotSplitIntoRetryStorm() {
        var service=mock(SeckillService.class);var commands=commands(service);
        doThrow(new IllegalStateException("database unavailable")).when(service).createPreparedOrders(anyList());
        assertEquals(2,new SeckillBatchCoordinator(service).fulfill(commands).size());verify(service,times(1)).createPreparedOrders(anyList());
    }
    @Test void preparationFailureDoesNotEnterWriteBatch() {
        var service=mock(SeckillService.class);var commands=commands(service);
        when(service.prepareSeckillOrder(commands.get(1))).thenThrow(new OrderBusinessException("地址不存在"));
        var errors=new SeckillBatchCoordinator(service).fulfill(commands);assertEquals(Set.of("bad"),errors.keySet());
        verify(service).createPreparedOrders(argThat(rows->rows.size()==1 && "good".equals(rows.get(0).command().getOrderNumber())));
    }
    @Test void sameActivityWaitsOutsideWriterServiceInsteadOfOpeningCompetingTransactions()throws Exception {
        var service=mock(SeckillService.class);var commands=commands(service);var coordinator=new SeckillBatchCoordinator(service);
        var active=new java.util.concurrent.atomic.AtomicInteger();var peak=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);try{Thread.sleep(15);}finally{active.decrementAndGet();}return null;}).when(service).createPreparedOrders(anyList());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {var jobs=new ArrayList<java.util.concurrent.Future<?>>();for(int i=0;i<8;i++)jobs.add(pool.submit(()->assertTrue(coordinator.fulfill(commands).isEmpty())));for(var job:jobs)job.get(10,java.util.concurrent.TimeUnit.SECONDS);}
        finally {pool.shutdownNow();}
        assertEquals(1,peak.get());
    }
    @Test void interruptedGateDoesNotStartWriteTransaction() {
        var service=mock(SeckillService.class);var commands=commands(service);
        try {
            Thread.currentThread().interrupt();assertEquals(2,new SeckillBatchCoordinator(service).fulfill(commands).size());
            assertTrue(Thread.currentThread().isInterrupted());verify(service,never()).createPreparedOrders(anyList());
        } finally {Thread.interrupted();}
    }
    private List<SeckillOrderMessageDTO> commands(SeckillService service) {
        var result=new ArrayList<SeckillOrderMessageDTO>();
        for(String number:List.of("good","bad")) {
            var command=SeckillOrderMessageDTO.builder().orderNumber(number).activityId(1L).build();result.add(command);
            when(service.prepareSeckillOrder(command)).thenReturn(new PreparedSeckillOrder(command,null,null));
        }
        when(service.prepareSeckillOrders(anyList())).thenAnswer(call->{
            List<SeckillOrderMessageDTO> portion=call.getArgument(0);
            var ready=new ArrayList<PreparedSeckillOrder>();var failures=new LinkedHashMap<String,Exception>();
            for(var command:portion)try {ready.add(service.prepareSeckillOrder(command));}
            catch(Exception failure){failures.put(command.getOrderNumber(),failure);}
            return new com.sky.service.SeckillPreparation(ready,failures);
        });
        return result;
    }
}
