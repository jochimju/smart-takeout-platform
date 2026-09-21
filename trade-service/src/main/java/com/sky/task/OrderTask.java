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

        if(ordersList != null && !ordersList.isEmpty()) {
            // 配送超时只能作为运营告警，不能替代骑手/用户确认等履约凭证。
            // 订单继续保持配送中，等待明确的完成事件。
            log.warn("{} delivery orders have been in progress for more than 60 minutes; no automatic completion is performed",
                    ordersList.size());
        }
    }

}
