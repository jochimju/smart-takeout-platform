package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.Coupon;
import com.sky.entity.UserCoupon;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.CouponMapper;
import com.sky.mapper.UserCouponMapper;
import com.sky.service.CouponService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class CouponServiceImpl implements CouponService {

    @Autowired
    private CouponMapper couponMapper;
    @Autowired
    private UserCouponMapper userCouponMapper;

    @Transactional
    public void receive(Long couponId) {
        if (couponId == null) {
            throw new OrderBusinessException("coupon id cannot be empty");
        }
        Long userId = BaseContext.getCurrentId();
        Coupon coupon = couponMapper.getById(couponId);
        LocalDateTime now = LocalDateTime.now();
        if (coupon == null || coupon.getStatus() == null || coupon.getStatus() != 1
                || now.isBefore(coupon.getBeginTime()) || now.isAfter(coupon.getEndTime())) {
            throw new OrderBusinessException("coupon is not available");
        }
        UserCoupon exists = userCouponMapper.getByUserIdAndCouponId(userId, couponId);
        if (exists != null) {
            throw new OrderBusinessException("coupon already received");
        }
        int affected = couponMapper.increaseReceivedCount(couponId);
        if (affected == 0) {
            throw new OrderBusinessException("coupon stock not enough");
        }
        userCouponMapper.insert(UserCoupon.builder()
                .userId(userId)
                .couponId(couponId)
                .status(UserCoupon.UNUSED)
                .receiveTime(now)
                .build());
    }
}
