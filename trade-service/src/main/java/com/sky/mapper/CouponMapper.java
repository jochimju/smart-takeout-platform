package com.sky.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sky.entity.Coupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CouponMapper extends BaseMapper<Coupon> {

    @Select("select * from coupon where id = #{id}")
    Coupon getById(@Param("id") Long id);

    @Update("update coupon set received_count = received_count + 1 where id = #{id} and received_count < total_stock")
    int increaseReceivedCount(@Param("id") Long id);
}
