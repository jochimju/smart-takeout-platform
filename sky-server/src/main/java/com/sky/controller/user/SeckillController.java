package com.sky.controller.user;

import com.sky.result.Result;
import com.sky.service.SeckillService;
import com.sky.vo.SeckillActivityVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/user/seckill/activity")
@Api(tags = "C端秒杀活动接口")
public class SeckillController {
    @Autowired private SeckillService seckillService;

    @GetMapping("/list")
    @ApiOperation("查询可展示的秒杀套餐")
    public Result<List<SeckillActivityVO>> list(Long canteenId) {
        return Result.success(seckillService.listAvailableActivities(canteenId));
    }
}
