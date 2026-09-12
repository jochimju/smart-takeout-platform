package com.sky.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** C 端展示所需的秒杀套餐、价格和倒计时信息。 */
@Data
public class SeckillActivityVO {
    private Long id;
    private Long setmealId;
    private String setmealName;
    private String image;
    private BigDecimal originalPrice;
    private BigDecimal seckillPrice;
    private Integer stock;
    private Integer remainingStock;
    private Integer monthlySales;
    /** C 端展示的单人限购数量。 */
    private Integer purchaseLimit;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private Integer status;
    /** C 端倒计时的结束时间戳（毫秒）。 */
    private Long endTimestamp;
    private Long beginTimestamp;
}
