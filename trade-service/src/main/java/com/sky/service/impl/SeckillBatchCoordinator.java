package com.sky.service.impl;

import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.exception.OrderBusinessException;
import com.sky.observability.SeckillTiming;
import com.sky.service.PreparedSeckillOrder;
import com.sky.service.SeckillService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Deterministic bad requests are split after whole-transaction rollback; outages are not split. */
@Service
@RequiredArgsConstructor
public class SeckillBatchCoordinator {
    private final SeckillService service;
    @org.springframework.beans.factory.annotation.Value("${sky.seckill.writer-gate-enabled:true}") private boolean gateEnabled=true;
    private final java.util.concurrent.Semaphore[] writers=createWriters();
    private static java.util.concurrent.Semaphore[] createWriters() {
        var gates=new java.util.concurrent.Semaphore[32];for(int i=0;i<gates.length;i++)gates[i]=new java.util.concurrent.Semaphore(1,true);return gates;
    }
    public Map<String,Exception> fulfill(List<SeckillOrderMessageDTO> commands) {
        Map<String,Exception> failures=new LinkedHashMap<>();Map<String,List<PreparedSeckillOrder>> groups=new LinkedHashMap<>();
        for(int from=0;from<commands.size();from+=16) {
            var portion=commands.subList(from,Math.min(commands.size(),from+16));
            try {
                var result=SeckillTiming.rootValue("prepare",()->service.prepareSeckillOrders(portion));
                failures.putAll(result.failures());
                for(var prepared:result.ready())
                    groups.computeIfAbsent(prepared.command().getActivityId()+":"+prepared.command().inventoryBucket(),ignored->new ArrayList<>()).add(prepared);
            } catch(Exception failure) {portion.forEach(command->failures.put(command.getOrderNumber(),failure));}
        }
        for(var group:groups.values()) {
            group.sort(Comparator.comparing(row->row.command().getOrderNumber()));
            for(int from=0;from<group.size();from+=16)write(group.subList(from,Math.min(group.size(),from+16)),failures);
        }
        return failures;
    }
    private void write(List<PreparedSeckillOrder> orders,Map<String,Exception> failures) {
        try {SeckillTiming.root("consume_batch",()->writeUnderGate(orders));}
        catch(Exception failure) {
            boolean deterministic=OrderFailurePolicy.permanent(failure);
            if(deterministic && orders.size()>1) {
                int middle=orders.size()/2;write(orders.subList(0,middle),failures);write(orders.subList(middle,orders.size()),failures);
            } else orders.forEach(row->failures.put(row.command().getOrderNumber(),failure));
        }
    }
    private void writeUnderGate(List<PreparedSeckillOrder> orders) {
        if(!gateEnabled){service.createPreparedOrders(orders);return;}
        var gate=writers[Math.floorMod(31*orders.get(0).command().getActivityId().hashCode()+orders.get(0).command().inventoryBucket(),writers.length)];
        boolean acquired=false;long started=System.nanoTime();
        try {
            gate.acquire();acquired=true;SeckillTiming.record("writer_gate_wait",System.nanoTime()-started);
            service.createPreparedOrders(orders);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("Seckill writer interrupted before transaction",interrupted);
        } finally {if(acquired)gate.release();}
    }
}
