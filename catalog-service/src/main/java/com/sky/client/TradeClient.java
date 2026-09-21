package com.sky.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 交易服务内部接口。餐厅删除前需要确认没有关联订单，而订单表属于交易库。
 */
@FeignClient(name = "trade-service", configuration = TradeFeignConfiguration.class)
public interface TradeClient {

    @GetMapping("/internal/trade/orders/count")
    Integer countOrdersByCanteen(@RequestParam("canteenId") Long canteenId);
}
