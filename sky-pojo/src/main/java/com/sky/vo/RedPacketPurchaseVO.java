package com.sky.vo;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class RedPacketPurchaseVO {
    private String purchaseOrderNo;
    private BigDecimal payAmount;
}
