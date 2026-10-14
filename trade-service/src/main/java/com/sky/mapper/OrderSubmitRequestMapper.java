package com.sky.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

/** Durable request-to-order binding used for normal-order idempotency. */
@Mapper
public interface OrderSubmitRequestMapper {
    @Select("select * from order_submit_request where user_id=#{user} and request_id=#{request}")
    java.util.Map<String,Object> find(@Param("user") Long user,@Param("request") String request);
    @Select("select * from order_submit_request where user_id=#{user} and request_id=#{request} lock in share mode")
    java.util.Map<String,Object> findCurrent(@Param("user") Long user,@Param("request") String request);

    @Select("select *,deadline_at<=now(3) expired from order_submit_request where order_number=#{number} for update")
    java.util.Map<String,Object> lock(String number);
    @Select("select * from order_submit_request where order_number=#{number}")
    java.util.Map<String,Object> get(String number);

    @Insert("insert ignore into order_submit_request(user_id,request_id,order_number,request_hash,command_body,create_time,deadline_at,next_at) values(#{user},#{request},#{number},#{hash},#{body},now(),date_add(now(3),interval 120 second),date_add(now(3),interval 10 second))")
    int claimSnapshot(@Param("user") Long user,@Param("request") String request,@Param("number") String number,@Param("hash") String hash,@Param("body") String body);

    @org.apache.ibatis.annotations.Update("update order_submit_request set state='SUCCEEDED',last_error=null where order_number=#{number} and state='ACCEPTED'")
    int succeeded(String number);

    @org.apache.ibatis.annotations.Update("update order_submit_request set state=if(#{permanent} or attempts>=7 or deadline_at<=now(3),'FAILED','ACCEPTED'),attempts=attempts+1,last_error=#{error},next_at=date_add(now(3),interval 5 second) where order_number=#{number} and state='ACCEPTED'")
    int failed(@Param("number") String number,@Param("error") String error,@Param("permanent") boolean permanent);

    @Select("select order_number from order_submit_request where state='ACCEPTED' and command_body is not null and next_at<=now(3) order by next_at limit 100")
    java.util.List<String> ready();

    @org.apache.ibatis.annotations.Update("update order_submit_request set next_at=date_add(now(3),interval 10 second) where order_number=#{number} and state='ACCEPTED'")
    int postpone(String number);

    // 用户与 requestId 的唯一约束配合 insert ignore，用返回行数判断是否取得提交资格。
    @Insert("insert ignore into order_submit_request(user_id, request_id, order_number, create_time) " +
            "values(#{userId}, #{requestId}, #{orderNumber}, now())")
    int claim(@Param("userId") Long userId, @Param("requestId") String requestId,
              @Param("orderNumber") String orderNumber);

    // 查询请求绑定只能得到订单号；还要查 orders 才知道建单是否完成。
    @Select("select order_number from order_submit_request where user_id=#{userId} and request_id=#{requestId}")
    String findOrderNumber(@Param("userId") Long userId, @Param("requestId") String requestId);

    /**
     * A request id must be released when its asynchronous command has reached
     * the dead-letter path without creating an order.  Restricting the delete
     * to the order number avoids releasing a newer request accidentally.
     */
    @Delete("delete from order_submit_request where order_number=#{orderNumber}")
    int releaseByOrderNumber(@Param("orderNumber") String orderNumber);
}
