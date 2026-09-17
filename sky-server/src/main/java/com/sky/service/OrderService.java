package com.sky.service;

import com.sky.dto.*;
import com.sky.result.PageResult;
import com.sky.vo.*;

public interface OrderService {

    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO);

    OrderCheckoutVO checkout();

    void createOrderFromMessage(OrderSubmitMessageDTO orderSubmitMessageDTO);

    void cancelTimeoutOrder(String orderNumber);

    /**
     * 订单支付
     * @param ordersPaymentDTO
     * @return
     */
    OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception;

    /**
     * Confirms a payment through the development-only mock payment channel.
     */
    void confirmMockPayment(String orderNumber);

    /**
     * 支付成功，修改订单状态
     * @param outTradeNo
     */
    void paySuccess(String outTradeNo);

    void paySuccess(String number,String transactionId,java.math.BigDecimal amount);

    /**
     * 用户端订单分页查询
     * @param page
     * @param pageSize
     * @param status
     * @return
     */
    PageResult pageQuery4User(int page, int pageSize, Integer status);

    /**
     * 查询订单详情
     * @param id
     * @return
     */
    OrderVO details(Long id);

    /**
     * 管理端查询订单详情：管理员可查看任意订单，不做订单归属校验
     * @param id
     * @return
     */
    OrderVO adminDetails(Long id);

    /**
     * 用户取消订单
     * @param id
     */
    void userCancelById(Long id) throws Exception;

    /** 用户从历史订单中删除已完成或已取消的订单。 */
    void deleteHistoryOrder(Long id);

    /**
     * 再来一单
     *
     * @param id
     */
    /**
     * 将历史订单恢复到当前用户购物车，并返回该订单所属的餐厅。
     */
    RestaurantVO repetition(Long id);

    /**
     * 条件搜索订单
     * @param ordersPageQueryDTO
     * @return
     */
    PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * 各个状态的订单数量统计
     * @return
     */
    OrderStatisticsVO statistics();

    /**
     * 接单
     *
     * @param ordersConfirmDTO
     */
    void confirm(OrdersConfirmDTO ordersConfirmDTO);

    /**
     * 拒单
     *
     * @param ordersRejectionDTO
     */
    void rejection(OrdersRejectionDTO ordersRejectionDTO) throws Exception;

    /**
     * 商家取消订单
     *
     * @param ordersCancelDTO
     */
    void cancel(OrdersCancelDTO ordersCancelDTO) throws Exception;

    /**
     * 派送订单
     *
     * @param id
     */
    void delivery(Long id);

    /**
     * 完成订单
     *
     * @param id
     */
    void complete(Long id);

    /**
     * 用户催单
     * @param id
     */
    void reminder(Long id);
}
