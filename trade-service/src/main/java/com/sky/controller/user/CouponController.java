package com.sky.controller.user;

import com.sky.dto.CouponReceiveDTO;
import com.sky.result.Result;
import com.sky.service.CouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/coupon")
@Api(tags = "coupon api")
public class CouponController {

    @Autowired
    private CouponService couponService;

    @PostMapping("/receive")
    @ApiOperation("receive coupon")
    public Result receive(@RequestBody CouponReceiveDTO couponReceiveDTO) {
        couponService.receive(couponReceiveDTO.getCouponId());
        return Result.success();
    }
}
