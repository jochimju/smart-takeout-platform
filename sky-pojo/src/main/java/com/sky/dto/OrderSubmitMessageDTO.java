package com.sky.dto;

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
public class OrderSubmitMessageDTO implements Serializable {

    private Long userId;
    private Long addressBookId;
    private int payMethod;
    private String remark;
    private LocalDateTime estimatedDeliveryTime;
    private Integer deliveryStatus;
    private Integer tablewareNumber;
    private Integer tablewareStatus;
    private Integer packAmount;
    private String orderNumber;
    private Long couponId;
    private Long userRedPacketId;
    private Boolean useRedPacket;

    /** 订单创建时锁定购物车所属餐厅，避免异步处理时串到其他餐厅的购物车。 */
    private Long canteenId;
    private String canteenName;
}
