package com.sky.security;

import com.sky.client.AccountClient;
import com.sky.config.RemoteAccountAccess;
import com.sky.context.BaseContext;
import com.sky.service.*;
import com.sky.utils.JwtUtil;
import com.sky.utils.WeChatPayUtil;
import com.sky.utils.properties.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={com.sky.controller.user.OrderController.class, com.sky.controller.notify.PayNotifyController.class, com.sky.controller.admin.PaymentOperationsController.class,com.sky.controller.admin.TradeRuntimeController.class}, properties={
        "spring.config.import=", "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "sky.jwt.admin-secret-key=test-admin", "sky.jwt.user-secret-key=test-user", "sky.internal.token=test-internal"})
@Import({ApiSecurityConfiguration.class, RemoteAccountAccess.class, JwtProperties.class, WeChatProperties.class}) @ActiveProfiles("test")
class TradeSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean com.sky.payment.PaymentSettlement settlement;
    @MockitoBean com.sky.service.impl.TradeRuntimeOperations runtime;
    @Test void runtimeReplayRequiresPermissionAndCarriesAuthenticatedOperator()throws Exception {
        String token=JwtUtil.createJWT("test-admin",60000,Map.of("empId",1L));when(accounts.valid(any())).thenReturn(true);
        String body="{\"source\":\"MQ\",\"key\":\"9\",\"reason\":\"broker recovered\"}";
        mvc.perform(post("/admin/order/runtime/replay").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/order/runtime/replay").header("token",token).contentType("application/json").content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(runtime);when(accounts.permitted(1L,"POST","/admin/order/runtime/replay")).thenReturn(true);
        mvc.perform(post("/admin/order/runtime/replay").header("token",token).contentType("application/json").content(body)).andExpect(status().isOk());
        verify(runtime).replay("MQ","9",1L,"broker recovered");assertNull(BaseContext.getCurrentId());
    }
    @MockitoBean com.sky.payment.PaymentRecoveryStore recovery;
    @MockitoBean com.sky.payment.PaymentReconciliation reconciliation;
    @MockitoBean AccountClient accounts; @MockitoBean OrderService orders; @MockitoBean SeckillService seckill;
    @MockitoBean RedPacketService redPackets; @MockitoBean WeChatPayUtil payment;
    @Test void transientOrderPersistenceFailureIs503AndRetainsRetryContract()throws Exception {
        String token=JwtUtil.createJWT("test-user",60000,Map.of("userId",9L));when(accounts.valid(any())).thenReturn(true);
        when(orders.submitOrder(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database offline"));
        mvc.perform(post("/user/order/submit").contentType("application/json").content("{}").header("authentication",token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(0));
        assertNull(BaseContext.getCurrentId());
    }
    @Test void orderSubmissionRetainsIdentityAndResponseContract() throws Exception {
        String token=JwtUtil.createJWT("test-user",60000,Map.of("userId",9L));
        when(accounts.valid(any())).thenReturn(true);
        when(orders.submitOrder(any())).thenAnswer(call -> {
            assertEquals(9L, BaseContext.getCurrentId()); return com.sky.vo.OrderSubmitVO.builder().orderNumber("TEST-ORDER").build();
        });
        mvc.perform(post("/user/order/submit").contentType("application/json").content("{}").header("authentication",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data.orderNumber").value("TEST-ORDER"));
        assertNull(BaseContext.getCurrentId());
        mvc.perform(post("/user/order/submit").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verify(orders,times(1)).submitOrder(any());
    }
    @Test void callbackRequiresSignatureEvenThoughLoginTokenIsNotRequired() throws Exception {
        doThrow(new SecurityException("bad signature")).when(payment).verifyNotification(anyString(),any(),any(),any(),any());
        mvc.perform(post("/notify/paySuccess").contentType("application/json").content("{}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("FAIL"));
        verify(payment).verifyNotification(eq("{}"),any(),any(),any(),any());
        verifyNoInteractions(orders,redPackets,accounts);
        mvc.perform(get("/notify/paySuccess")).andExpect(status().isUnauthorized());
    }
    @Test void paymentOperationsRequireAdminIdentityAndExplicitRbacPermission() throws Exception {
        String token=JwtUtil.createJWT("test-admin",60000,Map.of("empId",1L));
        when(accounts.valid(any())).thenReturn(true);
        mvc.perform(post("/admin/payment/recovery/replay").param("id","check:ORDER1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/payment/recovery/replay").param("id","check:ORDER1").header("token",token)).andExpect(status().isForbidden());
        verifyNoInteractions(recovery);
        when(accounts.permitted(1L,"POST","/admin/payment/recovery/replay")).thenReturn(true);when(recovery.replay("check:ORDER1")).thenReturn(1);
        mvc.perform(post("/admin/payment/recovery/replay").param("id","check:ORDER1").header("token",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(1));
    }
}
