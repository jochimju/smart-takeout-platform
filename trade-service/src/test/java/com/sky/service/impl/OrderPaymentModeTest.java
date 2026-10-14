package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.sky.client.AccountClient;
import com.sky.contract.account.AccountUserView;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.entity.Orders;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.OrderMapper;
import com.sky.utils.WeChatPayUtil;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderPaymentModeTest {
    OrderServiceImpl service;
    OrderMapper orders;
    AccountClient accounts;
    WeChatPayUtil wechat;
    OrderLifecycleService lifecycle;
    OrdersPaymentDTO request;
    @BeforeEach void setup() {
        service=new OrderServiceImpl(); orders=mock(OrderMapper.class); accounts=mock(AccountClient.class);
        wechat=mock(WeChatPayUtil.class); lifecycle=mock(OrderLifecycleService.class);
        ReflectionTestUtils.setField(service,"orderMapper",orders);
        ReflectionTestUtils.setField(service,"accountClient",accounts);
        ReflectionTestUtils.setField(service,"weChatPayUtil",wechat);
        ReflectionTestUtils.setField(service,"lifecycle",lifecycle);
        ReflectionTestUtils.setField(service,"paymentRecovery",mock(com.sky.payment.PaymentRecoveryStore.class));
        BaseContext.setCurrentId(7L); request=new OrdersPaymentDTO(); request.setOrderNumber("TEST7");request.setPayMethod(1);
        when(orders.getByNumberAndUserId("TEST7",7L)).thenReturn(Orders.builder().id(1L).number("TEST7").userId(7L)
                .amount(new BigDecimal("10.50")).status(1).payStatus(0).expireTime(LocalDateTime.now().plusMinutes(5)).build());
    }
    @AfterEach void clear() { BaseContext.removeCurrentId(); }
    @Test void localPaymentDoesNotNeedMerchantOrCallWechat() throws Exception {
        ReflectionTestUtils.setField(service,"mockPaymentEnabled",true);
        assertTrue(service.payment(request).getMockPayment());
        verifyNoInteractions(accounts,wechat,lifecycle);
        service.confirmMockPayment("TEST7"); verify(lifecycle).paid("TEST7","mock:TEST7",null,true);
    }
    @Test void realPaymentUsesServerAmountAndReturnsClientSignatureWithoutMarkingPaid() throws Exception {
        ReflectionTestUtils.setField(service,"mockPaymentEnabled",false);
        when(accounts.user(7L)).thenReturn(new AccountUserView("openid7"));
        JSONObject payment=new JSONObject();payment.put("timeStamp","123");payment.put("nonceStr","nonce");
        payment.put("package","prepay_id=test7");payment.put("signType","RSA");payment.put("paySign","signature");
        when(wechat.pay(org.mockito.ArgumentMatchers.eq("TEST7"),org.mockito.ArgumentMatchers.eq(new BigDecimal("10.50")),org.mockito.ArgumentMatchers.eq("Sky take-out order"),org.mockito.ArgumentMatchers.eq("openid7"),org.mockito.ArgumentMatchers.any())).thenReturn(payment);
        var result=service.payment(request);assertEquals("prepay_id=test7",result.getPackageStr());
        assertNotEquals(Boolean.TRUE,result.getMockPayment());verifyNoInteractions(lifecycle);
        assertThrows(OrderBusinessException.class,()->service.confirmMockPayment("TEST7"));
    }
    @Test void missingMerchantOrGatewayErrorNeverFallsBackToSimulatedSuccess() throws Exception {
        when(accounts.user(7L)).thenReturn(new AccountUserView("openid7"));
        when(wechat.pay(org.mockito.ArgumentMatchers.eq("TEST7"),org.mockito.ArgumentMatchers.eq(new BigDecimal("10.50")),org.mockito.ArgumentMatchers.eq("Sky take-out order"),org.mockito.ArgumentMatchers.eq("openid7"),org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("merchant not configured"));
        assertThrows(IllegalStateException.class,()->service.payment(request));verifyNoInteractions(lifecycle);
    }
    @Test void foreignOrClosedOrderCannotStartEitherPaymentMode() {
        when(orders.getByNumberAndUserId("TEST7",7L)).thenReturn(null);
        for(boolean mock:new boolean[]{true,false}) {
            ReflectionTestUtils.setField(service,"mockPaymentEnabled",mock);
            assertThrows(OrderBusinessException.class,()->service.payment(request));
        }
        verifyNoInteractions(accounts,wechat,lifecycle);
    }
}
