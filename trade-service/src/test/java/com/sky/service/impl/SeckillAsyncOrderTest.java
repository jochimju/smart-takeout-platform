package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.sky.client.AccountClient;
import com.sky.context.BaseContext;
import com.sky.contract.account.AccountAddressView;
import com.sky.contract.catalog.ProductQuote;
import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.dto.SeckillOrderSubmitDTO;
import com.sky.entity.*;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.*;
import com.sky.service.mq.OrderMessagePublisher;
import com.sky.vo.OrderSubmitVO;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeckillAsyncOrderTest {
    SeckillServiceImpl service;
    SeckillCache cache;
    SeckillActivityMapper activities;
    SeckillAsyncStore store;
    OrderMapper orders;
    OrderDetailMapper details;
    SeckillOrderGuardMapper guards;
    SeckillReservationMapper reservations;
    RedPacketMapper packets;
    CatalogQuoteService catalog;
    AccountClient account;
    OrderMessagePublisher publisher;

    @BeforeEach void setup() {
        service=new SeckillServiceImpl();cache=mock(SeckillCache.class);activities=mock(SeckillActivityMapper.class);
        store=mock(SeckillAsyncStore.class);orders=mock(OrderMapper.class);details=mock(OrderDetailMapper.class);
        guards=mock(SeckillOrderGuardMapper.class);reservations=mock(SeckillReservationMapper.class);
        packets=mock(RedPacketMapper.class);catalog=mock(CatalogQuoteService.class);account=mock(AccountClient.class);
        publisher=mock(OrderMessagePublisher.class);
        ReflectionTestUtils.setField(service,"cache",cache);ReflectionTestUtils.setField(service,"seckillActivityMapper",activities);
        ReflectionTestUtils.setField(service,"asyncStore",store);ReflectionTestUtils.setField(service,"orderMapper",orders);
        ReflectionTestUtils.setField(service,"orderDetailMapper",details);ReflectionTestUtils.setField(service,"seckillOrderGuardMapper",guards);
        ReflectionTestUtils.setField(service,"reservationMapper",reservations);ReflectionTestUtils.setField(service,"redPacketMapper",packets);
        ReflectionTestUtils.setField(service,"catalogQuoteService",catalog);ReflectionTestUtils.setField(service,"accountClient",account);
        ReflectionTestUtils.setField(service,"orderMessagePublisher",publisher);
        BaseContext.setCurrentId(1L);
    }
    @AfterEach void clearUser() {BaseContext.removeCurrentId();}
    SeckillOrderMessageDTO command() {
        var request=new SeckillOrderSubmitDTO();request.setActivityId(1L);request.setSetmealId(48L);
        request.setRequestId("request_0000000001");request.setAddressBookId(3L);request.setUseRedPacket(false);
        request.setAmount(new BigDecimal("15.90"));
        return SeckillOrderMessageDTO.builder().activityId(1L).userId(1L).setmealId(48L).orderNumber("SKtest")
            .request(request).requestHash("hash").price(new BigDecimal("8.90")).acceptedAt(System.currentTimeMillis()).build();
    }
    void accepted(SeckillOrderMessageDTO command) {
        when(store.get("SKtest")).thenReturn(Map.of("state","ACCEPTED","command_body",JSON.toJSONString(command)));
        when(store.lock("SKtest")).thenReturn(Map.of("state","ACCEPTED","expired",0));
        var activity=new SeckillActivity();activity.setId(1L);activity.setSetmealId(48L);
        when(activities.lock(1L)).thenReturn(activity);
        when(catalog.quoteSetmeal(48L)).thenReturn(ProductQuote.builder().productId(48L).name("套餐").status(1).build());
        when(account.address(1L,3L)).thenReturn(new AccountAddressView(3L,1L,"用户","13000000000","地址"));
    }
    @Test void httpOnlyAcceptsInRedisAndNeverCreatesSynchronously() {
        when(cache.accept(any())).thenReturn(OrderSubmitVO.builder().orderNumber("SKaccepted").submitStatus("ACCEPTED").build());
        assertEquals("ACCEPTED",service.seckillOrder(command().getRequest()).getSubmitStatus());
        verifyNoInteractions(activities,store,orders,details,guards,reservations,packets,catalog,account,publisher);
    }
    @Test void mysqlExhaustionPreventsOrderInsertion() {
        var command=command();accepted(command);when(activities.fulfill(1L)).thenReturn(0);
        assertThrows(OrderBusinessException.class,()->service.createSeckillOrder(command));
        verifyNoInteractions(orders,details,guards,reservations,publisher);
        verify(store,never()).succeeded(any());
    }
    @Test void terminalAcceptanceMakesLateDuplicateANoop() {
        when(store.get("SKtest")).thenReturn(Map.of("state","FAILED"));
        service.createSeckillOrder(command());
        verifyNoInteractions(activities,orders,details,guards,reservations,packets,catalog,account,publisher);
    }
    @Test void successfulCreationUsesAcceptancePriceAndSchedulesTimeout() {
        var command=command();accepted(command);when(activities.fulfill(1L)).thenReturn(1);
        doAnswer(call->{Orders order=call.getArgument(0);order.setId(9L);
            assertEquals(new BigDecimal("15.90"),order.getAmount());
            assertEquals(order.getOrderTime().plusMinutes(15).withNano(0),order.getExpireTime());return null;
        }).when(orders).insert(any());
        service.createSeckillOrder(command);
        var sequence=inOrder(activities,guards,orders,details,store,publisher);
        sequence.verify(activities).lock(1L);sequence.verify(store).lock("SKtest");
        sequence.verify(activities).fulfill(1L);sequence.verify(guards).insertGuard(1L,1L,48L,"SKtest");
        sequence.verify(orders).insert(any());sequence.verify(details).insertBatch(anyList());
        sequence.verify(store).succeeded(any());sequence.verify(publisher).timeoutDeferred(eq("SKtest"),any());
    }
}
