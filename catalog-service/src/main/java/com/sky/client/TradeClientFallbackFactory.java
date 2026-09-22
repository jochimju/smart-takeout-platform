package com.sky.client;

import com.sky.exception.RemoteServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/** Refuse destructive restaurant operations when the order reference check is unavailable. */
@Component
@Slf4j
public class TradeClientFallbackFactory implements FallbackFactory<TradeClient> {
    @Override
    public TradeClient create(Throwable cause) {
        log.warn("trade-service Feign fallback activated", cause);
        return canteenId -> {
            throw new RemoteServiceUnavailableException("订单服务暂不可用，无法确认餐厅关联情况，请稍后重试");
        };
    }
}
