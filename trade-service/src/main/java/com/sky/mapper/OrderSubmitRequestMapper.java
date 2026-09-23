package com.sky.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

/** Durable request-to-order binding used for normal-order idempotency. */
@Mapper
public interface OrderSubmitRequestMapper {

    @Insert("insert ignore into order_submit_request(user_id, request_id, order_number, create_time) " +
            "values(#{userId}, #{requestId}, #{orderNumber}, now())")
    int claim(@Param("userId") Long userId, @Param("requestId") String requestId,
              @Param("orderNumber") String orderNumber);

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
