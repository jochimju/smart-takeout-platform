package com.sky.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_coupon")
public class UserCoupon implements Serializable {

    public static final Integer UNUSED = 0;
    public static final Integer USED = 1;
    public static final Integer EXPIRED = 2;

    private Long id;
    private Long userId;
    private Long couponId;
    private Integer status;
    private LocalDateTime receiveTime;
    private LocalDateTime useTime;
    private Long orderId;
}
