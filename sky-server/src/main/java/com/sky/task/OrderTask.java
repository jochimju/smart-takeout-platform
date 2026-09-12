package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

/**
 * 鑷畾涔夊畾鏃朵换鍔★紝瀹炵幇璁㈠崟鐘舵€佸畾鏃跺鐞?
 */
@Component
@Slf4j
public class OrderTask {

    @Autowired
    private OrderMapper orderMapper;

    @Scheduled(cron = "0 * * * * ?")
    public void processDeliveryOrder(){
        log.info("澶勭悊娲鹃€佷腑璁㈠崟锛歿}", new Date());
        // select * from orders where status = 4 and order_time < 褰撳墠鏃堕棿-1灏忔椂
        LocalDateTime time = LocalDateTime.now().plusMinutes(-60);
        List<Orders> ordersList = orderMapper.getByStatusAndOrdertimeLT(Orders.DELIVERY_IN_PROGRESS, time);

        if(ordersList != null && ordersList.size() > 0){
            ordersList.forEach(order -> {
                order.setStatus(Orders.COMPLETED);
                orderMapper.transition(order.getId(),Orders.DELIVERY_IN_PROGRESS,Orders.COMPLETED);
            });
        }
    }

}
