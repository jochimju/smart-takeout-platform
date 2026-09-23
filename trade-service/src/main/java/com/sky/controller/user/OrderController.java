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
 * 閻犱降鍨瑰畷?
 */
@RestController("userOrderController")
@RequestMapping("/user/order")
@Slf4j
@Api(tags = "user order api")
public class OrderController {

    @Autowired
    private OrderService orderService;
    @GetMapping("/seckill/result")
    public Result<OrderSubmitVO> seckillResult(@RequestParam String requestId) {
        return Result.success(seckillService.findRequest(requestId));
    }

    @Autowired
    private com.sky.service.SeckillService seckillService;

    @PostMapping("/seckill/submit")
    @ApiOperation("秒杀活动下单")
    public Result<OrderSubmitVO> seckillSubmit(@RequestBody com.sky.dto.SeckillOrderSubmitDTO request) {
        return Result.success(seckillService.seckillOrder(request));
    }

    /**
     * 闁活潿鍔嶉崺娑欑▔鐎ｎ亜绀?
     *
     * @param ordersSubmitDTO
     * @return
     */
    @PostMapping("/submit")
    @ApiOperation("submit order")
    public Result<OrderSubmitVO> submit(@RequestBody OrdersSubmitDTO ordersSubmitDTO) {
        log.info("闁活潿鍔嶉崺娑欑▔鐎ｎ亜绀嬮柨娑欘劯}", ordersSubmitDTO);
        OrderSubmitVO orderSubmitVO = orderService.submitOrder(ordersSubmitDTO);
        return Result.success(orderSubmitVO);
    }

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
     * 閻犱降鍨瑰畷鐔煎绩椤栨瑧甯?
     *
     * @param ordersPaymentDTO
     * @return
     */
    @PutMapping("/payment")
    @ApiOperation("pay order")
    public Result<OrderPaymentVO> payment(@RequestBody OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        log.info("閻犱降鍨瑰畷鐔煎绩椤栨瑧甯涢柨娑欘劯}", ordersPaymentDTO);
        OrderPaymentVO orderPaymentVO = orderService.payment(ordersPaymentDTO);
        log.info("闁汇垻鍠愰崹姘紣閸曨剚鏆滃ù鐘趁煎锕傚及閹惧啿绀嬮柨娑欘劯}", orderPaymentVO);
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
     * 闁告ê妫楄ぐ鍓佹媼閵忕姴绀嬮柡灞诲劥椤?
     *
     * @param page
     * @param pageSize
     * @param status   閻犱降鍨瑰畷鐔兼偐閼哥鍋?1鐎垫澘鎳嶇划顖氣枎?2鐎垫澘鎳忕敮鎾础?3鐎圭寮剁敮鎾础?4婵炴煡绠栭埀顑挎閼?5鐎瑰憡褰冮悾顒勫箣?6鐎瑰憡褰冭ぐ鍥р槈?
     * @return
     */
    @GetMapping("/historyOrders")
    @ApiOperation("history orders")
    public Result<PageResult> page(int page, int pageSize, Integer status) {
        PageResult pageResult = orderService.pageQuery4User(page, pageSize, status);
        return Result.success(pageResult);
    }

    /**
     * 闁哄被鍎撮妤冩媼閵忕姴绀嬮悹鍥烽檮閸?
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
     * 闁活潿鍔嶉崺娑㈠矗閺嶃劎啸閻犱降鍨瑰畷?
     *
     * @return
     */
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
     * 闁告劕绉靛鍨▔閳ь剟宕?
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
     * 闁活潿鍔嶉崺娑㈠磼椤掆偓瀹?
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
