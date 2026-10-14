package com.sky.controller.admin;

import com.sky.payment.*;
import com.sky.result.Result;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/admin/payment") @RequiredArgsConstructor
public class PaymentOperationsController {
    private final PaymentRecoveryStore recovery;
    private final PaymentReconciliation reconciliation;
    @GetMapping("/recovery/failures") public Result<List<Map<String,Object>>> failures() { return Result.success(recovery.failures()); }
    @PostMapping("/recovery/replay") public Result<Integer> replay(@RequestParam String id) { return Result.success(recovery.replay(id)); }
    @PostMapping("/reconciliation/download") public Result<Map<String,Object>> download(@RequestParam LocalDate date) throws Exception { return Result.success(reconciliation.download(date)); }
    @PostMapping("/reconciliation/import") public Result<Map<String,Object>> upload(@RequestParam LocalDate date,@RequestParam MultipartFile file) throws Exception {
        if(file.isEmpty() || file.getSize()>8*1024*1024) throw new IllegalArgumentException("Bill file size invalid");
        return Result.success(reconciliation.upload(date,new String(file.getBytes(),java.nio.charset.StandardCharsets.UTF_8)));
    }
    @GetMapping("/reconciliation/runs") public Result<List<Map<String,Object>>> runs() { return Result.success(reconciliation.runs()); }
    @GetMapping("/reconciliation/differences") public Result<List<Map<String,Object>>> differences(@RequestParam long runId) { return Result.success(reconciliation.differences(runId)); }
}
