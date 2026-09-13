package com.sky.vo;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class OrderCheckoutVO {
    private BigDecimal foodAmount;
    private BigDecimal packAmount;
    private BigDecimal deliveryAmount;
    private BigDecimal discountAmount;
    private BigDecimal payableAmount;
    private Long defaultRedPacketId;
    private List<RedPacketCheckoutVO> redPackets;
}
