package com.sky.controller.user;
import com.sky.dto.*;
import com.sky.service.OrderService;
import com.sky.result.Result;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
/** Controlled same-business-work comparison; unavailable outside an isolated benchmark schema. */
@RestController @Profile("benchmark") @ConditionalOnProperty(name="sky.benchmark.enabled",havingValue="true")
@RequestMapping("/user/order/benchmark")
public class OrderBenchmarkController {
 private final OrderService orders;
 public OrderBenchmarkController(OrderService orders,@Value("${spring.datasource.url:${spring.datasource.druid.url:}}") String url) {
  if(!url.matches("jdbc:mysql://[^/]+/sky_arch_[A-Za-z0-9_]+[?].*"))throw new IllegalStateException("Benchmark endpoint requires an isolated sky_arch schema");this.orders=orders;
 }
 @PostMapping("/sync") public Result submit(@RequestBody OrdersSubmitDTO request) {
  var accepted=orders.submitOrder(request);
  if(!"SUCCEEDED".equals(accepted.getSubmitStatus())) {
   var received=OrderSubmitMessageDTO.builder().orderNumber(accepted.getOrderNumber()).build();
   orders.createPreparedNormalOrder(orders.prepareNormalOrder(received));
  }
  return Result.success(orders.findSubmitResult(request.getRequestId()));
 }
}
