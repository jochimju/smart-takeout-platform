package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.dto.OrderSubmitMessageDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.contract.account.AccountAddressView;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.exception.OrderBusinessException;
import com.sky.client.AccountClient;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.OrderSubmitRequestMapper;
import com.sky.mapper.ShoppingCartMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OrderSubmissionSecurityTest {

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    @Test
    void otherUsersOrderCannotBeReadRepeatedOrReminded() {
        BaseContext.setCurrentId(11L);
        OrderMapper orders = mock(OrderMapper.class);
        OrderDetailMapper details = mock(OrderDetailMapper.class);
        BusinessEventOutbox outbox = mock(BusinessEventOutbox.class);
        OrderServiceImpl service = new OrderServiceImpl();
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "orderDetailMapper", details);
        ReflectionTestUtils.setField(service, "businessEventOutbox", outbox);

        when(orders.getByIdAndUserId(99L, 11L)).thenReturn(null);

        assertThrows(OrderBusinessException.class, () -> service.details(99L));
        assertThrows(OrderBusinessException.class, () -> service.repetition(99L));
        assertThrows(OrderBusinessException.class, () -> service.reminder(99L));
        verify(details, never()).getByOrderId(anyLong());
        verifyNoInteractions(outbox);
    }

    @Test
    void duplicateRequestReturnsTheOriginallyCreatedOrder() {
        BaseContext.setCurrentId(11L);
        OrderMapper orders = mock(OrderMapper.class);
        OrderSubmitRequestMapper requests = mock(OrderSubmitRequestMapper.class);
        OrderServiceImpl service = new OrderServiceImpl();
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "orderSubmitRequestMapper", requests);
        Orders persisted = Orders.builder().id(7L).number("20260913A").amount(new BigDecimal("18.00")).build();
        when(requests.findOrderNumber(11L, "normal_order_request_001")).thenReturn("20260913A");
        when(orders.getByNumberAndUserId("20260913A", 11L)).thenReturn(persisted);
        OrdersSubmitDTO dto = new OrdersSubmitDTO();
        dto.setRequestId("normal_order_request_001");

        assertEquals(7L, service.submitOrder(dto).getId());
        verify(requests, never()).claim(anyLong(), anyString(), anyString());
    }

    @Test
    void normalOrderRequiresAClientIdempotencyKey() {
        BaseContext.setCurrentId(11L);
        OrderServiceImpl service = new OrderServiceImpl();

        assertThrows(OrderBusinessException.class, () -> service.submitOrder(new OrdersSubmitDTO()));
    }

    @Test
    void closedShopStopsOrderBeforeAnyCartMutation() {
        BaseContext.setCurrentId(11L);
        OrderMapper orders = mock(OrderMapper.class);
        OrderSubmitRequestMapper requests = mock(OrderSubmitRequestMapper.class);
        AccountClient addresses = mock(AccountClient.class);
        RedisTemplate redis = mock(RedisTemplate.class);
        ValueOperations values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("SHOP_STATUS")).thenReturn(0);
        when(addresses.address(11L, 1L)).thenReturn(new AccountAddressView());
        OrderServiceImpl service = new OrderServiceImpl();
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "orderSubmitRequestMapper", requests);
        ReflectionTestUtils.setField(service, "accountClient", addresses);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        OrdersSubmitDTO dto = new OrdersSubmitDTO();
        dto.setRequestId("normal_order_request_002");
        dto.setAddressBookId(1L);

        assertThrows(OrderBusinessException.class, () -> service.submitOrder(dto));
        verify(requests, never()).claim(anyLong(), anyString(), anyString());
    }

    @Test
    void zeroQuantityAndOffSaleDishAreRejectedBeforeOrderPersistence() {
        BaseContext.setCurrentId(11L);
        AccountClient addresses = mock(AccountClient.class);
        ShoppingCartMapper carts = mock(ShoppingCartMapper.class);
        OrderMapper orders = mock(OrderMapper.class);
        RedisTemplate redis = mock(RedisTemplate.class);
        ValueOperations values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("SHOP_STATUS")).thenReturn(1);
        when(addresses.address(11L, 1L)).thenReturn(new AccountAddressView());
        ShoppingCart zero = new ShoppingCart(); zero.setDishId(1L); zero.setNumber(0);
        when(carts.list(any(ShoppingCart.class))).thenReturn(Collections.singletonList(zero));
        OrderServiceImpl service = new OrderServiceImpl();
        ReflectionTestUtils.setField(service, "accountClient", addresses);
        ReflectionTestUtils.setField(service, "shoppingCartMapper", carts);
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertThrows(OrderBusinessException.class, () -> service.createOrderFromMessage(
                OrderSubmitMessageDTO.builder().userId(11L).addressBookId(1L).orderNumber("x").packAmount(1).tablewareNumber(0).build()));
        verify(orders, never()).insert(any(Orders.class));

        ShoppingCart one = new ShoppingCart(); one.setDishId(1L); one.setNumber(1);
        CatalogQuoteService catalog = mock(CatalogQuoteService.class);
        when(carts.list(any(ShoppingCart.class))).thenReturn(Collections.singletonList(one));
        doThrow(new OrderBusinessException("商品已停售")).when(catalog).refresh(anyList());
        ReflectionTestUtils.setField(service, "catalogQuoteService", catalog);

        assertThrows(OrderBusinessException.class, () -> service.createOrderFromMessage(
                OrderSubmitMessageDTO.builder().userId(11L).addressBookId(1L).orderNumber("y").packAmount(1).tablewareNumber(0).build()));
        verify(orders, never()).insert(any(Orders.class));
    }
}
