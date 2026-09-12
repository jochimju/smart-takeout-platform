package com.sky.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.math.BigDecimal;

/** 可购买一个套餐的限时秒杀活动。 */
@Data
public class SeckillActivity {
    private Long id;
    private Long setmealId;
    private Integer stock;
    private Integer remainingStock;
    private Long stockVersion;
    /** 单个用户在本活动中可购买的套餐份数，当前秒杀规则固定为 1。 */
    private Integer purchaseLimit;
    /** 秒杀成交价，原价取套餐当前 price。 */
    private BigDecimal seckillPrice;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    /** 1 启用，0 停用。 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
