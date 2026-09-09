package com.sky.controller.user;

import com.sky.result.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

@RestController("userShopController")
@RequestMapping("/user/shop")
@Api(tags = "user shop api")
@Slf4j
public class ShopController {

    public static final String KEY = "SHOP_STATUS";
    private static final Integer DEFAULT_STATUS = 1;

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 闁兼儳鍢茶ぐ鍥ㄦ償濡ゅ懏鎳欓柣銊ュ閹偓濞戞挻姘ㄦ慨鎼佸箑?
     * @return
     */
    @GetMapping("/status")
    @ApiOperation("get shop status")
    public Result<Integer> getStatus(){
        Integer status = (Integer) redisTemplate.opsForValue().get(KEY);
        if (status == null) {
            status = DEFAULT_STATUS;
            redisTemplate.opsForValue().set(KEY, status);
        }
        log.info("get shop status: {}", status);
        return Result.success(status);
    }
}
