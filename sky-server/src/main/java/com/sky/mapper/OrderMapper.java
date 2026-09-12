package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.dto.GoodsSalesDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {
    /**
     * 插入订单数据
     * @param order
     */
    void insert(Orders order);

    /**
     * 根据订单号查询订单
     * @param orderNumber
     */
    @Select("select * from orders where number = #{orderNumber}")
    Orders getByNumber(String orderNumber);

    /**
     * 修改订单信息
     * @param orders
     */
    void update(Orders orders);

    /**
     * 分页条件查询并按下单时间排序
     * @param ordersPageQueryDTO
     */
    Page<Orders> pageQuery(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * 根据id查询订单
     * @param id
     */
    @Select("select * from orders where id=#{id}")
    Orders getById(Long id);

    /**
     * 根据状态统计订单数量
     * @param status
     */
    @Select("select count(id) from orders where status = #{status}")
    Integer countStatus(Integer status);

    /**
     * 根据状态和下单时间查询订单
     * @param status
     * @param orderTime
     */
    @Select("select * from orders where status = #{status} and order_time < #{orderTime}")
    List<Orders> getByStatusAndOrdertimeLT(@Param("status") Integer status, @Param("orderTime") LocalDateTime orderTime);

    @Select("select * from orders where number = #{outTradeNo} and user_id = #{userId}")
    Orders getByNumberAndUserId(@Param("outTradeNo") String outTradeNo, @Param("userId") Long userId);

    /**
     * 根据动态条件统计营业额
     * @param map
     */
    Double sumByMap(Map map);

    /**
     * 查询商品销量排名
     * @param begin
     * @param end
     */
    List<GoodsSalesDTO> getSalesTop10(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end);

    /**
     * 根据条件统计菜品数量
     * @param map
     * @return
     */
    Integer countByMap(Map map);

    @Select("select * from orders where id=#{id} for update")
    Orders lockById(Long id);
    @Select("select * from orders where number=#{number} for update")
    Orders lockByNumber(String number);

    @org.apache.ibatis.annotations.Update("update orders set status=6,cancel_time=#{now}," +
        "cancel_reason=case when #{rejected}=false then #{reason} else cancel_reason end," +
        "rejection_reason=case when #{rejected}=true then #{reason} else rejection_reason end " +
        "where id=#{id} and status=#{status} and pay_status=#{payStatus} " +
        "and (#{timeout}=false or (status=1 and pay_status=0 and " +
        "coalesce(expire_time,date_add(order_time,interval 15 minute))<=#{now}))")
    int cancelIfCurrent(@Param("id") Long id,@Param("status") Integer status,@Param("payStatus") Integer payStatus,
        @Param("reason") String reason,@Param("rejected") boolean rejected,@Param("timeout") boolean timeout,
        @Param("now") LocalDateTime now);

    @org.apache.ibatis.annotations.Update("update orders set status=2,pay_status=1,checkout_time=now() where id=#{id} and status=1 and pay_status=0")
    int markPaid(Long id);

    @org.apache.ibatis.annotations.Update("update orders set status=#{next},delivery_time=case when #{next}=5 then now() else delivery_time end " +
        "where id=#{id} and status=#{expected} and pay_status=1")
    int transition(@Param("id") Long id,@Param("expected") int expected,@Param("next") int next);
}
