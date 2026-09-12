package com.sky.service.impl;

import com.sky.entity.SeckillReservation;
import com.sky.mapper.SeckillReservationMapper;
import com.sky.mapper.SeckillOrderGuardMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;

@Service
public class SeckillReleaseProcessor {
    @Autowired private SeckillReservationMapper reservationMapper;
    @Autowired private SeckillOrderGuardMapper guardMapper;
    @Autowired private StringRedisTemplate redis;
    @Autowired private com.sky.mapper.OrderMapper orders;
    @Autowired @org.springframework.context.annotation.Lazy private SeckillReservationService reservations;

    // 订单级完成标记避免数据库提交失败后重试，误释放用户新订单的资格。
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[3]) == 1 then return 0 end " +
            "if redis.call('srem', KEYS[2], ARGV[1]) == 1 and redis.call('exists', KEYS[1]) == 1 then " +
            "redis.call('incr', KEYS[1]) end " +
            "redis.call('set', KEYS[3], '1') return 1", Long.class);

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String number) {
        SeckillReservation snapshot=reservationMapper.get(number);
        if(snapshot!=null && snapshot.getActivityId()!=null) {
            com.sky.entity.Orders order=orders.lockByNumber(number);
            if(order!=null && com.sky.entity.Orders.CANCELLED.equals(order.getStatus())) reservations.cancel(order);
            return;
        }
        SeckillReservation reservation = reservationMapper.lock(number);
        if (reservation == null || reservation.getReleaseStatus() != 1) return;
        boolean activity = reservation.getActivityId() != null;
        Long id = activity ? reservation.getActivityId() : reservation.getSetmealId();
        redis.execute(RELEASE, Arrays.asList(
                (activity ? "seckill:activity:stock:" : "seckill:stock:") + id,
                (activity ? "seckill:user:" : "seckill:users:") + id,
                "seckill:released:" + number), reservation.getUserId().toString());
        // 限定订单号，不能删掉同一用户后续的新订单限购记录。
        guardMapper.deleteByOrderNumber(number);
        reservationMapper.markReleased(number);
    }
}
