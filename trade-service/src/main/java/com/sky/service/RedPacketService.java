package com.sky.service;

import com.sky.dto.RedPacketPurchaseDTO;
import com.sky.vo.OrderCheckoutVO;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.RedPacketCheckoutVO;
import com.sky.vo.RedPacketPurchaseVO;
import java.math.BigDecimal;
import java.util.List;

public interface RedPacketService {
    Object packages();
    RedPacketPurchaseVO createPurchase(RedPacketPurchaseDTO dto);
    OrderPaymentVO payment(String purchaseOrderNo) throws Exception;
    List<RedPacketCheckoutVO> myAvailable();
    OrderCheckoutVO checkout();
    boolean paySuccess(String orderNo, String transactionId, BigDecimal amount);
    void confirmMockPayment(String purchaseOrderNo);
}
