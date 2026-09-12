package com.sky.entity;

import lombok.Data;

/** 保留订单与秒杀库存的关联，以及取消后的释放进度。 */
@Data
public class SeckillReservation {
    private String orderNumber;
    private Long activityId;
    private Long setmealId;
    private Long userId;
    private Integer releaseStatus;
    private String requestId;
    private String requestHash;
}
