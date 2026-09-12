package com.sky.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 管理端创建或修改秒杀活动的参数。 */
@Data
public class SeckillActivityDTO {
    private Long id;
    private Long setmealId;
    private Integer stock;
    private Integer purchaseLimit;
    private BigDecimal seckillPrice;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private Integer status;
}
