package com.sky.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RedPacketPurchaseOrder {
    public static final Integer PENDING = 0;
    public static final Integer PAID = 1;
    public static final Integer CLOSED = 2;
    private Long id;
    private String orderNo;
    private Long userId;
    private Long packageId;
    private BigDecimal payAmount;
    private Integer packetCountSnapshot;
    private BigDecimal packetAmountSnapshot;
    private Integer validMonthsSnapshot;
    private Integer status;
    private String transactionId;
    private LocalDateTime createTime;
    private LocalDateTime paidTime;
}
