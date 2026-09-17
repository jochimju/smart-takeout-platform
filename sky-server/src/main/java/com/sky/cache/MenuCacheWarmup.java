package com.sky.cache;

import com.sky.constant.StatusConstant;
import com.sky.entity.Category;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.mapper.CategoryMapper;
import com.sky.service.DishService;
import com.sky.service.SetmealService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@Slf4j
public class MenuCacheWarmup {
    private final CategoryMapper categories;
    private final DishService dishes;
    private final SetmealService setmeals;
    private final MenuCache cache;
    private final boolean enabled;
    public MenuCacheWarmup(CategoryMapper categories, DishService dishes, SetmealService setmeals,
                           MenuCache cache, @Value("${sky.cache.warmup-enabled:true}") boolean enabled) {
        this.categories = categories; this.dishes = dishes; this.setmeals = setmeals;
        this.cache = cache; this.enabled = enabled;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        if (!enabled) return;
        int completed = 0;
        int failed = 0;
        try {
            // 预热全部餐厅的启用分类；具体 C 端查询仍由餐厅接口按 canteenId 过滤。
            List<Category> active = categories.list(null, null);
            for (Category category : active) {
                try {
                    if (Integer.valueOf(1).equals(category.getType())) {
                        cache.warm("dish", category.getId(), () -> dishes.listWithFlavor(Dish.builder()
                                .categoryId(category.getId()).status(StatusConstant.ENABLE).build()));
                    } else if (Integer.valueOf(2).equals(category.getType())) {
                        cache.warm("setmeal", category.getId(), () -> setmeals.list(Setmeal.builder()
                                .categoryId(category.getId()).status(StatusConstant.ENABLE).build()));
                    } else continue;
                    completed++;
                } catch (RuntimeException ex) {
                    failed++;
                    log.warn("Menu warmup failed for category {}", category.getId(), ex);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Cannot load categories for menu warmup; requests will rebuild lazily", ex);
            return;
        }
        log.info("Menu warmup completed: categories={}, failures={}", completed, failed);
    }
}
