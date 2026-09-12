package com.sky.controller.admin;

import com.sky.result.Result;
import com.sky.dto.SeckillActivityDTO;
import com.sky.vo.SeckillActivityVO;
import com.sky.service.SeckillService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import java.util.List;

/** 活动创建或库存调整后调用预热接口，使资格与库存进入 Redis。 */
@RestController
@RequestMapping("/admin/seckill/activity")
@Api(tags = "秒杀活动运维接口")
public class SeckillActivityController {
    @PutMapping("/{activityId}/stock")
    @ApiOperation("调整总库存，保留已经占用的数量")
    public Result adjustStock(@PathVariable Long activityId, @RequestBody StockAdjustment request) {
        if(request==null || request.total==null || request.expectedTotal==null)
            throw new com.sky.exception.OrderBusinessException("请提供目标总库存和原总库存");
        seckillService.adjustStock(activityId,request.total,request.expectedTotal);
        return Result.success();
    }
    public static class StockAdjustment { public Integer total; public Integer expectedTotal; }
    @Autowired
    private SeckillService seckillService;

    @PostMapping("/{activityId}/warm-up")
    @ApiOperation("预热秒杀活动库存与资格")
    public Result warmUp(@PathVariable Long activityId) {
        seckillService.warmUpActivity(activityId);
        return Result.success();
    }

    @GetMapping
    @ApiOperation("查询秒杀活动")
    public Result<List<SeckillActivityVO>> list() { return Result.success(seckillService.listActivities()); }

    @PostMapping
    @ApiOperation("创建秒杀活动")
    public Result create(@RequestBody SeckillActivityDTO activity) { seckillService.saveActivity(activity); return Result.success(); }

    @PutMapping
    @ApiOperation("修改秒杀活动")
    public Result update(@RequestBody SeckillActivityDTO activity) { seckillService.saveActivity(activity); return Result.success(); }

    @DeleteMapping("/{activityId}")
    @ApiOperation("删除秒杀活动")
    public Result delete(@PathVariable Long activityId) { seckillService.deleteActivity(activityId); return Result.success(); }
}
