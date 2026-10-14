package com.sky.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrdersSubmitDTO implements Serializable {
    /**
     * Client-generated idempotency key. One key is bound to one user order and
     * must be retained while retrying a request after a network failure.
     */
    private String requestId;

    //地址簿id
    private Long addressBookId;
    //付款方式
    private int payMethod;
    //备注
    private String remark;
    //预计送达时间
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime estimatedDeliveryTime;
    //配送状态  1立即送出  0选择具体时间
    private Integer deliveryStatus;
    //餐具数量
    private Integer tablewareNumber;
    //餐具数量状态  1按餐量提供  0选择具体数量
    private Integer tablewareStatus;
    //打包费
    private Integer packAmount;
    //总金额
    private BigDecimal amount;

    private Long couponId;

    /** 下单时用户所在的餐厅，用于订单归属和"再来一单"定位原餐厅。 */
    private Long canteenId;
    private String canteenName;

    /** A concrete red-packet asset, not the legacy coupon template id. */
    private Long userRedPacketId;

    /**
     * False means the user explicitly chose not to use a red packet. Null keeps
     * the default behavior: automatically use the earliest-expiring one.
     */
    private Boolean useRedPacket;
}
