package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.*;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderCancellationTest {
    private final OrderMapper orders=mock(OrderMapper.class);
    private final OrderDetailMapper details=mock(OrderDetailMapper.class);
    private final TradeInventoryService inventory=mock(TradeInventoryService.class);
    private final UserCouponMapper coupons=mock(UserCouponMapper.class);
    private final OrderReliabilityStore jobs=mock(OrderReliabilityStore.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final BusinessEventOutbox outbox=mock(BusinessEventOutbox.class);
    private final OrderLifecycleService service=new OrderLifecycleService(orders,details,inventory,coupons,
        mock(SeckillReservationService.class),jobs,outbox,jdbc);
    private Orders order;

    @BeforeEach void setup() {
        BaseContext.setCurrentId(1L);
        order=Orders.builder().id(9L).userId(1L).number("test-cancel").status(2).payStatus(1)
            .amount(new BigDecimal("18.00")).orderTime(LocalDateTime.now()).build();
        when(orders.lockById(9L)).thenReturn(order);
        when(orders.cancelIfCurrent(anyLong(),anyInt(),anyInt(),any(),anyBoolean(),anyBoolean(),any())).thenReturn(1);
        when(details.getByOrderId(9L)).thenReturn(Collections.singletonList(OrderDetail.builder().dishId(1L).number(2).build()));
    }
    @AfterEach void cleanup() { BaseContext.removeCurrentId(); }

    @Test void paidCancellationQueuesRefundAndRestoresStockOnce() {
        service.cancel(9L,"user","USER");
        verify(inventory).rollback(anyList()); verify(coupons).rollbackByOrderId(9L);
        verify(jobs).refund(order,false); verify(jobs).done("timeout:test-cancel");
        service.cancel(9L,"user","USER");
        verify(inventory,times(1)).rollback(anyList());
    }
    @Test void failedConditionalUpdateCannotRestoreStockOrRefund() {
        when(orders.cancelIfCurrent(anyLong(),anyInt(),anyInt(),any(),anyBoolean(),anyBoolean(),any())).thenReturn(0);
        assertThrows(OrderBusinessException.class,()->service.cancel(9L,"user","USER"));
        verifyNoInteractions(inventory,coupons,jobs);
    }
    @Test void anotherUsersOrderCannotBeCancelled() {
        BaseContext.setCurrentId(2L);
        assertThrows(OrderBusinessException.class,()->service.cancel(9L,"user","USER"));
        verifyNoInteractions(inventory,coupons,jobs);
    }
    @Test void completedOrderCannotBeCancelledByAdmin() {
        order.setStatus(5);
        assertThrows(OrderBusinessException.class,()->service.cancel(9L,"admin","ADMIN"));
        verifyNoInteractions(inventory,coupons,jobs);
    }
}
