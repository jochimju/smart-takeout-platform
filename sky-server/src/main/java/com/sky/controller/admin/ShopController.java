package com.sky.controller.admin;

import com.sky.result.Result;
import com.sky.constant.RedisKeyConstant;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("adminShopController")
@RequestMapping("/admin/shop")
@Api(tags = "admin shop api")
@Slf4j
public class ShopController {

    public static final String KEY = RedisKeyConstant.SHOP_STATUS;
    private static final Integer DEFAULT_STATUS = 1;

    @Autowired
    private RedisTemplate redisTemplate;

    @PutMapping("/{status}")
    @ApiOperation("set shop status")
    public Result setStatus(@PathVariable Integer status) {
        if (!Integer.valueOf(0).equals(status) && !Integer.valueOf(1).equals(status)) {
            return Result.error("shop status must be 0 or 1");
        }
        log.info("set shop status: {}", status == 1 ? "open" : "closed");
        redisTemplate.opsForValue().set(KEY, status);
        return Result.success();
    }

    @GetMapping("/status")
    @ApiOperation("get shop status")
    public Result<Integer> getStatus() {
        Integer status = (Integer) redisTemplate.opsForValue().get(KEY);
        if (status == null) {
            status = DEFAULT_STATUS;
            redisTemplate.opsForValue().set(KEY, status);
        }
        log.info("get shop status: {}", status);
        return Result.success(status);
    }
}
