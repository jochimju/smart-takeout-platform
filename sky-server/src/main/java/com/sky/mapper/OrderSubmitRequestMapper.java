package com.sky.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Durable request-to-order binding used for normal-order idempotency. */
@Mapper
public interface OrderSubmitRequestMapper {

    @Insert("insert ignore into order_submit_request(user_id, request_id, order_number, create_time) " +
            "values(#{userId}, #{requestId}, #{orderNumber}, now())")
    int claim(@Param("userId") Long userId, @Param("requestId") String requestId,
              @Param("orderNumber") String orderNumber);

    @Select("select order_number from order_submit_request where user_id=#{userId} and request_id=#{requestId}")
    String findOrderNumber(@Param("userId") Long userId, @Param("requestId") String requestId);
}
