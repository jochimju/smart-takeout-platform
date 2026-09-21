package com.sky.controller.internal;

import com.sky.mapper.CanteenOrderStatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 交易服务内部接口，仅供其他服务通过 Feign 调用（不经过网关公开）。
 */
@RestController
@RequestMapping("/internal/trade")
@RequiredArgsConstructor
public class TradeInternalController {

    private final CanteenOrderStatMapper canteenOrderStatMapper;

    @Value("${sky.internal.token}")
    private String internalToken;

    @GetMapping("/orders/count")
    public Integer countOrdersByCanteen(@RequestHeader("X-Internal-Token") String token,
                                        @RequestParam("canteenId") Long canteenId) {
        if (!Objects.equals(token, internalToken)) {
            throw new IllegalArgumentException("invalid internal token");
        }
        return canteenOrderStatMapper.countByCanteen(canteenId);
    }
}
