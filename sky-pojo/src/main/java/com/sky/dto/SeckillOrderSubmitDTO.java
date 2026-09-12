package com.sky.dto;

import lombok.Data;

/** 秒杀套餐下单参数；套餐和活动均由服务端再次校验。 */
@Data
public class SeckillOrderSubmitDTO extends OrdersSubmitDTO {
    private Long activityId;
    private Long setmealId;
    private String requestId;
}
