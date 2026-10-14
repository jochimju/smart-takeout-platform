package com.sky.service.impl;
import com.sky.controller.user.OrderBenchmarkController;
import com.sky.service.OrderService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class BenchmarkIsolationTest {
 @Test void comparisonEndpointCannotStartAgainstBusinessDatabase(){
  var orders=mock(OrderService.class);
  assertThrows(IllegalStateException.class,()->new OrderBenchmarkController(orders,"jdbc:mysql://localhost:3306/sky_take_out?useSSL=false"));
  assertDoesNotThrow(()->new OrderBenchmarkController(orders,"jdbc:mysql://localhost:3306/sky_arch_test_trade?useSSL=false"));
 }
}
