package com.sky.controller.admin;

import com.sky.dto.OrdersCancelDTO;
import com.sky.dto.OrdersConfirmDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersRejectionDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.OrderService;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 閻犱降鍨瑰畷鐔虹不閿涘嫭鍊?
 */
@RestController("adminOrderController")
@RequestMapping("/admin/order")
@Slf4j
@Api(tags = "admin order api")
public class OrderController {

    @Autowired
    private OrderService orderService;

    /**
     * 閻犱降鍨瑰畷鐔煎箹濠婂懎鍋?
     *
     * @param ordersPageQueryDTO
     * @return
     */
    @GetMapping("/conditionSearch")
    @ApiOperation("search orders")
    public Result<PageResult> conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageResult pageResult = orderService.conditionSearch(ordersPageQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 闁告艾瀚柌婊堟偐閼哥鍋撴担鐑樼暠閻犱降鍨瑰畷鐔煎极娴兼潙娅ょ紓浣哄枙椤?
     *
     * @return
     */
    @GetMapping("/statistics")
    @ApiOperation("order statistics")
    public Result<OrderStatisticsVO> statistics() {
        OrderStatisticsVO orderStatisticsVO = orderService.statistics();
        return Result.success(orderStatisticsVO);
    }

    /**
     * 閻犱降鍨瑰畷鐔烘嫚閿旇棄鍓?
     *
     * @param id
     * @return
     */
    @GetMapping("/details/{id}")
    @ApiOperation("order details")
    public Result<OrderVO> details(@PathVariable("id") Long id) {
        OrderVO orderVO = orderService.details(id);
        return Result.success(orderVO);
    }

    /**
     * 闁规亽鍎卞畷?
     *
     * @return
     */
    @PutMapping("/confirm")
    @ApiOperation("confirm order")
    public Result confirm(@RequestBody OrdersConfirmDTO ordersConfirmDTO) {
        orderService.confirm(ordersConfirmDTO);
        return Result.success();
    }

    /**
     * 闁归攱甯掑畷?
     *
     * @return
     */
    @PutMapping("/rejection")
    @ApiOperation("reject order")
    public Result rejection(@RequestBody OrdersRejectionDTO ordersRejectionDTO) throws Exception {
        orderService.rejection(ordersRejectionDTO);
        return Result.success();
    }

    /**
     * 闁告瑦鐗楃粔椋庢媼閵忕姴绀?
     *
     * @return
     */
    @PutMapping("/cancel")
    @ApiOperation("cancel order")
    public Result cancel(@RequestBody OrdersCancelDTO ordersCancelDTO) throws Exception {
        orderService.cancel(ordersCancelDTO);
        return Result.success();
    }

    /**
     * 婵炴煡绠栭埀顑挎祰椤撳綊宕?
     *
     * @return
     */
    @PutMapping("/delivery/{id}")
    @ApiOperation("deliver order")
    public Result delivery(@PathVariable("id") Long id) {
        orderService.delivery(id);
        return Result.success();
    }

    /**
     * 閻庣懓鏈崹姘辨媼閵忕姴绀?
     *
     * @return
     */
    @PutMapping("/complete/{id}")
    @ApiOperation("complete order")
    public Result complete(@PathVariable("id") Long id) {
        orderService.complete(id);
        return Result.success();
    }
}
