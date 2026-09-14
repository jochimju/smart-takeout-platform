package com.sky.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RedPacketPackage {
    private Long id;
    private String name;
    private BigDecimal salePrice;
    private Integer packetCount;
    private BigDecimal packetAmount;
    private Integer validMonths;
    private Integer status;
    private Integer purchaseLimitPerUser;
    private BigDecimal budgetAmount;
    private BigDecimal reservedAmount;
    private BigDecimal issuedAmount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
