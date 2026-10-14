package com.sky.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessageDTO implements Serializable {

    private Long activityId;
    private Integer bucketId;
    private Integer bucketCount;
    public int inventoryBucket() { return bucketId == null ? 0 : bucketId; }
    public int inventoryBuckets() { return bucketCount == null ? 1 : bucketCount; }
    private SeckillOrderSubmitDTO request;
    private String requestHash;
    private java.math.BigDecimal price;
    private Long acceptedAt;
    private Long userId;
    private Long setmealId;
    private String orderNumber;
}
