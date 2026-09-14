package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.dto.RedPacketPurchaseDTO;
import com.sky.entity.RedPacketPackage;
import com.sky.entity.RedPacketPurchaseOrder;
import com.sky.entity.UserRedPacket;
import com.sky.mapper.RedPacketMapper;
import com.sky.mapper.UserMapper;
import com.sky.utils.WeChatPayUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedPacketServiceImplTest {
    private final RedPacketMapper mapper = mock(RedPacketMapper.class);
    private final RedPacketServiceImpl service = new RedPacketServiceImpl(mapper, mock(UserMapper.class), mock(WeChatPayUtil.class));

    @AfterEach
    void cleanup() {
        BaseContext.removeCurrentId();
    }

    @Test
    void purchaseSnapshotsTheConfiguredPackage() {
        BaseContext.setCurrentId(7L);
        RedPacketPackage item = new RedPacketPackage();
        item.setId(1L);
        item.setSalePrice(new BigDecimal("10.00"));
        item.setPacketCount(2);
        item.setPacketAmount(new BigDecimal("5.00"));
        item.setValidMonths(1);
        item.setPurchaseLimitPerUser(1);
        when(mapper.lockActivePackage(1L)).thenReturn(item);
        when(mapper.reserveBudget(eq(1L), eq(new BigDecimal("10.00")))).thenReturn(1);

        RedPacketPurchaseDTO dto = new RedPacketPurchaseDTO();
        dto.setPackageId(1L);
        assertEquals(new BigDecimal("10.00"), service.createPurchase(dto).getPayAmount());
        verify(mapper).insertPurchase(argThat(order -> order.getUserId().equals(7L)
                && order.getPacketCountSnapshot() == 2
                && new BigDecimal("5.00").equals(order.getPacketAmountSnapshot())));
    }

    @Test
    void verifiedPaymentIssuesExactlyFivePackets() {
        RedPacketPurchaseOrder order = new RedPacketPurchaseOrder();
        order.setId(20L);
        order.setOrderNo("RP-test");
        order.setUserId(7L);
        order.setPackageId(1L);
        order.setPayAmount(new BigDecimal("10.00"));
        order.setPacketCountSnapshot(5);
        order.setPacketAmountSnapshot(new BigDecimal("5.00"));
        order.setPacketTotalAmountSnapshot(new BigDecimal("25.00"));
        order.setValidMonthsSnapshot(1);
        order.setStatus(RedPacketPurchaseOrder.PENDING);
        order.setExpireTime(LocalDateTime.now().plusMinutes(10));
        when(mapper.lockPurchase("RP-test")).thenReturn(order);
        when(mapper.markPurchasePaid(eq(20L), eq("wx-transaction"), any(LocalDateTime.class))).thenReturn(1);
        when(mapper.settleBudget(eq(1L), eq(new BigDecimal("25.00")))).thenReturn(1);

        assertTrue(service.paySuccess("RP-test", "wx-transaction", new BigDecimal("10.00")));
        verify(mapper, times(5)).insertUserRedPacket(argThat(packet ->
                UserRedPacket.UNUSED.equals(packet.getStatus())
                        && new BigDecimal("5.00").equals(packet.getAmount())
                        && packet.getExpireTime().isAfter(packet.getReceiveTime())));
    }
}
