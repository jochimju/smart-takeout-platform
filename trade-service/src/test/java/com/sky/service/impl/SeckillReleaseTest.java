package com.sky.service.impl;
import com.sky.entity.*;
import com.sky.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SeckillReleaseTest {
 @Test void repeatedCancellationRestoresExactlyOnce() {
  SeckillReservationService service=new SeckillReservationService();
  SeckillReservationMapper mapper=mock(SeckillReservationMapper.class);
  SeckillActivityMapper activities=mock(SeckillActivityMapper.class);
  SeckillOrderGuardMapper guards=mock(SeckillOrderGuardMapper.class);
  ReflectionTestUtils.setField(service,"mapper",mapper); ReflectionTestUtils.setField(service,"activities",activities); ReflectionTestUtils.setField(service,"guards",guards);
  SeckillReservation r=new SeckillReservation();r.setActivityId(1L);
  when(mapper.get("SK-test")).thenReturn(r);when(mapper.lock("SK-test")).thenReturn(r);
  when(activities.lock(1L)).thenReturn(new SeckillActivity());when(mapper.releaseOnce("SK-test")).thenReturn(1,0);when(activities.restore(1L)).thenReturn(1);
  Orders order=Orders.builder().number("SK-test").build();assertTrue(service.cancel(order));assertTrue(service.cancel(order));
  verify(activities,times(1)).restore(1L);verify(guards,times(1)).deleteByOrderNumber("SK-test");
 }
 @Test void ordinaryOrdersKeepOrdinaryStockPath() {
  SeckillReservationService service=new SeckillReservationService();ReflectionTestUtils.setField(service,"mapper",mock(SeckillReservationMapper.class));
  assertFalse(service.cancel(Orders.builder().number("ordinary").build()));
 }
}
