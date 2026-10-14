package com.sky.controller.user;

import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.OrderService;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 订单
 */
@RestController("userOrderController")
@RequestMapping("/user/order")
@Slf4j
@Api(tags = "user order api")
public class OrderController {

    @Autowired
    private OrderService orderService;
    @GetMapping("/seckill/result")
    public Result<OrderSubmitVO> seckillResult(@RequestParam String requestId, @RequestParam(required=false) Long activityId) {
        return Result.success(seckillService.findRequest(requestId, activityId));
    }

    @Autowired
    private com.sky.service.SeckillService seckillService;

    @PostMapping("/seckill/submit")
    @ApiOperation("秒杀活动下单")
    public Result<OrderSubmitVO> seckillSubmit(@RequestBody com.sky.dto.SeckillOrderSubmitDTO request) {
        return Result.success(seckillService.seckillOrder(request));
    }

    /**
     * 用户下单
     *
     * @param ordersSubmitDTO
     * @return
     */
    /** 普通下单受理；首次成功不保证订单已落库，用同一 requestId 轮询结果。 */
    @PostMapping("/submit")
    @ApiOperation("submit order")
    public Result<OrderSubmitVO> submit(@RequestBody OrdersSubmitDTO ordersSubmitDTO) {
        log.info("用户下单：{}", ordersSubmitDTO);
        OrderSubmitVO orderSubmitVO = orderService.submitOrder(ordersSubmitDTO);
        return Result.success(orderSubmitVO);
    }

    /** 查询当前用户提交对应的真实订单，前端取得 id 和金额后进入支付页。 */
    @GetMapping("/submit-result")
    @ApiOperation("query asynchronously submitted order result")
    public Result<OrderSubmitVO> submitResult(@RequestParam String requestId) {
        return Result.success(orderService.findSubmitResult(requestId));
    }

    @GetMapping("/checkout")
    @ApiOperation("preview order checkout")
    public Result<com.sky.vo.OrderCheckoutVO> checkout() {
        return Result.success(orderService.checkout());
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    @PutMapping("/payment")
    @ApiOperation("pay order")
    public Result<OrderPaymentVO> payment(@RequestBody OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        log.info("订单支付：{}", ordersPaymentDTO);
        OrderPaymentVO orderPaymentVO = orderService.payment(ordersPaymentDTO);
        log.info("生成预支付交易单：{}", orderPaymentVO);
        return Result.success(orderPaymentVO);
    }

    /**
     * Local development mock gateway callback. It is enabled only by the
     * development payment configuration.
     */
    @PutMapping("/payment/mock/confirm")
    @ApiOperation("confirm mock payment")
    public Result<String> confirmMockPayment(@RequestBody OrdersPaymentDTO ordersPaymentDTO) {
        orderService.confirmMockPayment(ordersPaymentDTO.getOrderNumber());
        return Result.success();
    }

    /**
     * 历史订单查询
     *
     * @param page
     * @param pageSize
     * @param status   订单状态 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
     * @return
     */
    @GetMapping("/historyOrders")
    @ApiOperation("history orders")
    public Result<PageResult> page(int page, int pageSize, Integer status) {
        PageResult pageResult = orderService.pageQuery4User(page, pageSize, status);
        return Result.success(pageResult);
    }

    /**
     * 查询订单详情
     *
     * @param id
     * @return
     */
    @GetMapping("/orderDetail/{id}")
    @ApiOperation("order detail")
    public Result<OrderVO> details(@PathVariable("id") Long id) {
        OrderVO orderVO = orderService.details(id);
        return Result.success(orderVO);
    }

    /**
     * 用户取消订单
     *
     * @return
     */
    /** 取消已创建订单；生命周期服务负责归属校验、状态限制和资源释放。 */
    @PutMapping("/cancel/{id}")
    @ApiOperation("cancel order")
    public Result cancel(@PathVariable("id") Long id) throws Exception {
        orderService.userCancelById(id);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @ApiOperation("delete a completed or cancelled order from user history")
    public Result deleteHistoryOrder(@PathVariable("id") Long id) {
        orderService.deleteHistoryOrder(id);
        return Result.success();
    }

    /**
     * 再来一单
     *
     * @param id
     * @return
     */
    @PostMapping("/repetition/{id}")
    @ApiOperation("reorder")
    public Result<java.util.Map<String, Object>> repetition(@PathVariable Long id) {
        return Result.success(orderService.repetition(id));
    }
    /**
     * 用户催单
     *
     * @param id
     * @return
     */
    @GetMapping("/reminder/{id}")
    @ApiOperation("remind order")
    public Result reminder(@PathVariable("id") Long id) {
        orderService.reminder(id);
        return Result.success();
    }

}
