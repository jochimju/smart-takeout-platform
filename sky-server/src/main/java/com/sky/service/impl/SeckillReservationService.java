package com.sky.service.impl;

import com.sky.entity.Orders;
import com.sky.entity.SeckillReservation;
import com.sky.mapper.SeckillReservationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Slf4j
public class SeckillReservationService {
    @Autowired private SeckillReservationMapper mapper;
    @Autowired private SeckillReleaseProcessor processor;
    @Autowired private com.sky.mapper.SeckillActivityMapper activities;
    @Autowired private com.sky.mapper.SeckillOrderGuardMapper guards;

    /** 必须在取消订单事务内调用；返回是否使用活动库存而非普通套餐库存。 */
    public boolean cancel(Orders order) {
        SeckillReservation reservation = mapper.get(order.getNumber());
        if (reservation == null) return false;
        if(reservation.getActivityId()!=null) {
            if(activities.lock(reservation.getActivityId())==null)
                throw new com.sky.exception.OrderBusinessException("订单关联活动缺失，不能释放库存");
            reservation=mapper.lock(order.getNumber());
            if(mapper.releaseOnce(order.getNumber())==1) {
                if(activities.restore(reservation.getActivityId())!=1)
                    throw new com.sky.exception.OrderBusinessException("秒杀库存账不一致，取消已回滚");
                guards.deleteByOrderNumber(order.getNumber());
            }
            return true;
        }
        reservation=mapper.lock(order.getNumber());
        mapper.markPending(order.getNumber());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { releaseSafely(order.getNumber()); }
        });
        return reservation.getActivityId() != null;
    }

    @Scheduled(fixedDelay = 5000)
    public void retryPending() {
        for (String number : mapper.pending()) releaseSafely(number);
    }

    private void releaseSafely(String number) {
        try { processor.release(number); }
        catch (Exception e) { log.error("秒杀订单 {} 资格释放失败，将自动重试", number, e); }
    }
}
