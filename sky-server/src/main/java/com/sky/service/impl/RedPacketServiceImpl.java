package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.sky.context.BaseContext;
import com.sky.dto.RedPacketPurchaseDTO;
import com.sky.entity.*;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.*;
import com.sky.service.RedPacketService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RedPacketServiceImpl implements RedPacketService {
    private final RedPacketMapper redPackets;
    private final UserMapper users;
    private final WeChatPayUtil weChatPay;
    @Value("$" + "{sky.payment.mock-enabled:false}")
    private boolean mockPaymentEnabled;

    @Override
    public Object packages() {
        return redPackets.activePackages();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RedPacketPurchaseVO createPurchase(RedPacketPurchaseDTO dto) {
        if (dto == null || dto.getPackageId() == null) throw new OrderBusinessException("red packet package is required");
        RedPacketPackage item = redPackets.activePackage(dto.getPackageId());
        if (item == null) throw new OrderBusinessException("red packet package is unavailable");
        RedPacketPurchaseOrder order = new RedPacketPurchaseOrder();
        order.setOrderNo("RP" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        order.setUserId(BaseContext.getCurrentId());
        order.setPackageId(item.getId());
        order.setPayAmount(item.getSalePrice());
        order.setPacketCountSnapshot(item.getPacketCount());
        order.setPacketAmountSnapshot(item.getPacketAmount());
        order.setValidMonthsSnapshot(item.getValidMonths());
        order.setStatus(RedPacketPurchaseOrder.PENDING);
        order.setCreateTime(LocalDateTime.now());
        redPackets.insertPurchase(order);
        return RedPacketPurchaseVO.builder().purchaseOrderNo(order.getOrderNo()).payAmount(order.getPayAmount()).build();
    }

    @Override
    public OrderPaymentVO payment(String purchaseOrderNo) throws Exception {
        RedPacketPurchaseOrder order = redPackets.purchaseByNumber(purchaseOrderNo);
        if (order == null || !BaseContext.getCurrentId().equals(order.getUserId()) || !RedPacketPurchaseOrder.PENDING.equals(order.getStatus())) {
            throw new OrderBusinessException("red packet purchase order is not payable");
        }
        if (mockPaymentEnabled) {
            return OrderPaymentVO.builder().mockPayment(true).build();
        }
        User user = users.getById(order.getUserId());
        JSONObject result = weChatPay.pay(order.getOrderNo(), order.getPayAmount(), "Sky red packet package", user.getOpenid());
        if (result.getString("code") != null) throw new OrderBusinessException("payment request failed: " + result.getString("code"));
        OrderPaymentVO vo = result.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(result.getString("package"));
        return vo;
    }

    @Override
    public List<RedPacketCheckoutVO> myAvailable() {
        return redPackets.availableByUser(BaseContext.getCurrentId()).stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public OrderCheckoutVO checkout() {
        List<RedPacketCheckoutVO> packets = myAvailable();
        return OrderCheckoutVO.builder().defaultRedPacketId(packets.isEmpty() ? null : packets.get(0).getId()).redPackets(packets).build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean paySuccess(String orderNo, String transactionId, BigDecimal amount) {
        if (orderNo == null || !orderNo.startsWith("RP")) return false;
        RedPacketPurchaseOrder order = redPackets.lockPurchase(orderNo);
        if (order == null) throw new OrderBusinessException("red packet purchase order not found");
        if (amount == null || order.getPayAmount().compareTo(amount) != 0) throw new OrderBusinessException("red packet payment amount mismatch");
        if (RedPacketPurchaseOrder.PAID.equals(order.getStatus())) {
            if (!transactionId.equals(order.getTransactionId())) throw new OrderBusinessException("conflicting red packet payment receipt");
            return true;
        }
        if (!RedPacketPurchaseOrder.PENDING.equals(order.getStatus())) throw new OrderBusinessException("red packet purchase order state invalid");
        LocalDateTime paidAt = LocalDateTime.now();
        if (redPackets.markPurchasePaid(order.getId(), transactionId, paidAt) != 1) throw new OrderBusinessException("red packet payment state changed");
        for (int sequence = 1; sequence <= order.getPacketCountSnapshot(); sequence++) {
            UserRedPacket packet = new UserRedPacket();
            packet.setUserId(order.getUserId());
            packet.setPackageId(order.getPackageId());
            packet.setPurchaseOrderId(order.getId());
            packet.setSequenceNo(sequence);
            packet.setAmount(order.getPacketAmountSnapshot());
            packet.setStatus(UserRedPacket.UNUSED);
            packet.setReceiveTime(paidAt);
            packet.setExpireTime(paidAt.plusMonths(order.getValidMonthsSnapshot()));
            redPackets.insertUserRedPacket(packet);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmMockPayment(String purchaseOrderNo) {
        if (!mockPaymentEnabled) throw new OrderBusinessException("mock payment is disabled");
        RedPacketPurchaseOrder order = redPackets.purchaseByNumber(purchaseOrderNo);
        if (order == null || !BaseContext.getCurrentId().equals(order.getUserId())) {
            throw new OrderBusinessException("red packet purchase order not found");
        }
        paySuccess(order.getOrderNo(), "mock:" + order.getOrderNo(), order.getPayAmount());
    }

    private RedPacketCheckoutVO toVO(UserRedPacket packet) {
        return RedPacketCheckoutVO.builder().id(packet.getId()).amount(packet.getAmount()).expireTime(packet.getExpireTime()).build();
    }
}
