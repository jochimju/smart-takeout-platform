package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderTaskTest {
    @Test
    void overdueDeliveryIsReportedButNeverAutoCompleted() {
        OrderMapper mapper = mock(OrderMapper.class);
        Orders order = new Orders(); order.setId(1L);
        when(mapper.getByStatusAndOrdertimeLT(eq(Orders.DELIVERY_IN_PROGRESS), any())).thenReturn(Collections.singletonList(order));
        OrderTask task = new OrderTask();
        ReflectionTestUtils.setField(task, "orderMapper", mapper);

        task.processDeliveryOrder();

        verify(mapper, never()).transition(anyLong(), anyInt(), anyInt());
    }
}
