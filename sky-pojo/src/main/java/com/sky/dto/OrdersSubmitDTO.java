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

    //鍦板潃绨縤d
    private Long addressBookId;
    //浠樻鏂瑰紡
    private int payMethod;
    //澶囨敞
    private String remark;
    //棰勮閫佽揪鏃堕棿
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime estimatedDeliveryTime;
    //閰嶉€佺姸鎬? 1绔嬪嵆閫佸嚭  0閫夋嫨鍏蜂綋鏃堕棿
    private Integer deliveryStatus;
    //椁愬叿鏁伴噺
    private Integer tablewareNumber;
    //椁愬叿鏁伴噺鐘舵€? 1鎸夐閲忔彁渚? 0閫夋嫨鍏蜂綋鏁伴噺
    private Integer tablewareStatus;
    //鎵撳寘璐?
    private Integer packAmount;
    //鎬婚噾棰?
    private BigDecimal amount;

    private Long couponId;

    /** A concrete red-packet asset, not the legacy coupon template id. */
    private Long userRedPacketId;

    /**
     * False means the user explicitly chose not to use a red packet. Null keeps
     * the default behavior: automatically use the earliest-expiring one.
     */
    private Boolean useRedPacket;
}
