package com.sky.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sky.entity.UserCoupon;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    @Select("select * from user_coupon where user_id = #{userId} and coupon_id = #{couponId}")
    UserCoupon getByUserIdAndCouponId(@Param("userId") Long userId, @Param("couponId") Long couponId);

    @Insert("insert into user_coupon(user_id, coupon_id, status, receive_time) values(#{userId}, #{couponId}, #{status}, #{receiveTime})")
    int insert(UserCoupon userCoupon);

    @Update("update user_coupon set status = 1, use_time = now(), order_id = #{orderId} where id = #{id} and status = 0")
    int markUsed(@Param("id") Long id, @Param("orderId") Long orderId);

    @Update("update user_coupon set status = 0, use_time = null, order_id = null where order_id = #{orderId} and status = 1")
    int rollbackByOrderId(@Param("orderId") Long orderId);
}
