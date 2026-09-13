package com.sky.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class UserRedPacket {
    public static final Integer UNUSED = 0;
    public static final Integer RESERVED = 1;
    public static final Integer USED = 2;
    public static final Integer EXPIRED = 3;
    private Long id;
    private Long userId;
    private Long packageId;
    private Long purchaseOrderId;
    private Integer sequenceNo;
    private BigDecimal amount;
    private Integer status;
    private LocalDateTime receiveTime;
    private LocalDateTime expireTime;
    private LocalDateTime usedTime;
    private Long foodOrderId;
}
