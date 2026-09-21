package com.sky.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SeckillOrderGuardMapper {
    @org.apache.ibatis.annotations.Select("select user_id,order_number from seckill_order_guard where activity_id=#{id}")
    java.util.List<com.sky.entity.SeckillReservation> users(Long id);

    @org.apache.ibatis.annotations.Delete("delete from seckill_order_guard where order_number=#{number}")
    int deleteByOrderNumber(String number);

    @Insert("insert into seckill_order_guard(user_id, activity_id, setmeal_id, order_number, create_time) values(#{userId}, #{activityId}, #{setmealId}, #{orderNumber}, now())")
    int insertGuard(@Param("userId") Long userId,
                    @Param("activityId") Long activityId,
                    @Param("setmealId") Long setmealId,
                    @Param("orderNumber") String orderNumber);
}
