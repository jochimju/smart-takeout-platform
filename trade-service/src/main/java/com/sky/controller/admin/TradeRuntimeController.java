package com.sky.controller.admin;
import com.sky.context.BaseContext;
import com.sky.result.Result;
import com.sky.service.impl.TradeRuntimeOperations;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/admin/order/runtime") @RequiredArgsConstructor
public class TradeRuntimeController {
 private final TradeRuntimeOperations operations;
 @GetMapping("/backlog") public Result backlog(){return Result.success(operations.backlog());}
 @PostMapping("/archive") public Result archive(@RequestParam(defaultValue="30") int retentionDays){return Result.success(operations.archive(retentionDays));}
 @PostMapping("/retire/{activityId}") public Result retire(@PathVariable Long activityId){return Result.success(operations.retire(activityId));}
 public record Replay(String source,String key,String reason){}
 @PostMapping("/replay") public Result replay(@RequestBody Replay request){operations.replay(request.source(),request.key(),BaseContext.getCurrentId(),request.reason());return Result.success();}
}
