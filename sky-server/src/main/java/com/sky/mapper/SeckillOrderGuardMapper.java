package com.sky.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SeckillOrderGuardMapper {

    @Insert("insert into seckill_order_guard(user_id, setmeal_id, order_number, create_time) values(#{userId}, #{setmealId}, #{orderNumber}, now())")
    int insertGuard(@Param("userId") Long userId,
                    @Param("setmealId") Long setmealId,
                    @Param("orderNumber") String orderNumber);
}
