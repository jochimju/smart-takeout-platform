package com.sky.controller.admin;
import com.sky.result.Result;
import com.sky.service.impl.TradeInventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/admin/inventory") @RequiredArgsConstructor
public class InventoryController {
 private final TradeInventoryService inventory;
 public record Adjustment(String adjustmentId,String productType,Long productId,int delta){}
 @PostMapping("/adjust") public Result adjust(@RequestBody Adjustment command) {
  inventory.adjust(command.adjustmentId(),command.productType(),command.productId(),command.delta());return Result.success();
 }
}
