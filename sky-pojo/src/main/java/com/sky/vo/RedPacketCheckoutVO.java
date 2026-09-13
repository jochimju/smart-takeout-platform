package com.sky.vo;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class RedPacketCheckoutVO {
    private Long id;
    private BigDecimal amount;
    private LocalDateTime expireTime;
}
