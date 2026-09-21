package com.sky.task;

import com.sky.entity.RedPacketPurchaseOrder;
import com.sky.mapper.RedPacketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Releases reserved packet budget after an unpaid purchase expires. */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedPacketPurchaseTask {
    private final RedPacketMapper redPackets;

    @Scheduled(cron = "0 * * * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void closeExpiredPurchases() {
        for (RedPacketPurchaseOrder order : redPackets.expiredPendingPurchases()) {
            if (redPackets.closeExpiredPurchase(order.getId()) == 1) {
                redPackets.releaseBudget(order.getPackageId(), order.getPacketTotalAmountSnapshot());
                log.info("closed expired red packet purchase {}", order.getOrderNo());
            }
        }
    }
}
