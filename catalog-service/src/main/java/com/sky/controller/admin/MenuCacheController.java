package com.sky.controller.admin;

import com.sky.cache.MenuCacheMetrics;
import com.sky.result.Result;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/admin/cache")
public class MenuCacheController {
    private final MenuCacheMetrics metrics;
    private final com.sky.cache.MenuCacheInvalidationOutbox invalidations;
    public MenuCacheController(MenuCacheMetrics metrics, com.sky.cache.MenuCacheInvalidationOutbox invalidations) {
        this.metrics = metrics; this.invalidations = invalidations;
    }
    /** 管理端 JWT 拦截器保护；单实例累计值，重启归零。 */
    @GetMapping("/stats")
    public Result<Map<String, Map<String, Number>>> stats() {
        return Result.success(metrics.snapshot());
    }
    @GetMapping("/invalidations")
    public Result<Map<String, Object>> invalidations() { return Result.success(invalidations.summary()); }
}
