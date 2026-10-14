package com.sky.service.mq;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TimeoutRoutingTest {
 @Test void roundingNeverClosesEarlyAndAddsLessThanTenSeconds() {
  for(long remaining=1;remaining<=900000;remaining+=137) {
   String route=TimeoutRouting.route(remaining);long ttl=Long.parseLong(route.substring(route.lastIndexOf('.')+1))*1000;
   assertTrue(ttl>=remaining && ttl-remaining<10000);
  }
  assertEquals(com.sky.constant.MqConstant.ORDER_DEAD_ROUTING_KEY,TimeoutRouting.route(0));
  assertThrows(IllegalArgumentException.class,()->TimeoutRouting.route(900001));
 }
}
