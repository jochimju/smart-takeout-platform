package com.sky.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.dto.SeckillOrderSubmitDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.Orders;
import com.sky.entity.Setmeal;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeckillOrderDefaultsTest {
    @Test
    void quickBuyPayloadSuppliesRequiredDatabaseValues() throws Exception {
        // 与小程序实际发送的四个字段一致：不经过普通订单结算页。
        SeckillOrderSubmitDTO request = new ObjectMapper().readValue(
                "{\"activityId\":1,\"setmealId\":48,\"addressBookId\":3,\"payMethod\":1}",
                SeckillOrderSubmitDTO.class);
        Orders order = build(request);
        assertEquals(Integer.valueOf(1), order.getDeliveryStatus());
        assertEquals(Integer.valueOf(1), order.getTablewareStatus());
        assertEquals(BigDecimal.ZERO, order.getDiscountAmount());
        assertEquals(new BigDecimal("15.90"), order.getAmount());
        assertEquals(1, order.getPackAmount());
        assertEquals(Long.valueOf(3), order.getAddressBookId());
    }

    @Test
    void explicitDeliveryAndTablewareChoicesArePreserved() {
        SeckillOrderSubmitDTO request = new SeckillOrderSubmitDTO();
        request.setDeliveryStatus(0);
        request.setTablewareStatus(0);
        request.setTablewareNumber(2);
        request.setPackAmount(999);
        Orders order = build(request);
        assertEquals(Integer.valueOf(0), order.getDeliveryStatus());
        assertEquals(Integer.valueOf(0), order.getTablewareStatus());
        assertEquals(2, order.getTablewareNumber());
        assertEquals(1, order.getPackAmount());
    }

    private Orders build(SeckillOrderSubmitDTO request) {
        return ReflectionTestUtils.invokeMethod(new SeckillServiceImpl(), "buildOrder",
                request, 1L, AddressBook.builder().id(3L).build(),
                Setmeal.builder().id(48L).build(), new BigDecimal("8.90"),
                "test-only-order", LocalDateTime.now());
    }
}
