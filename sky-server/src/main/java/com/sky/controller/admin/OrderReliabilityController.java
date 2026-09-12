package com.sky.controller.admin;

import com.sky.result.Result;
import com.sky.service.impl.OrderReliabilityStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/admin/order/reliability")
@RequiredArgsConstructor
public class OrderReliabilityController {
    private final OrderReliabilityStore store;
    @GetMapping("/failures")
    public Result<List<Map<String,Object>>> failures() { return Result.success(store.failures()); }
    @PostMapping("/replay")
    public Result<Integer> replay(@RequestParam String id) { return Result.success(store.replay(id)); }
}
