package com.sky.listener;

import com.sky.event.DishStatusChangedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sky.cache.MenuCache;

@Component
public class DishStatusCacheListener {
    @Autowired
    private MenuCache menuCache;
    @Autowired
    private CacheManager cacheManager;

    // 仅提交成功后失效；回滚时保留原缓存。
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidate(DishStatusChangedEvent event) {
        try {
            menuCache.invalidateDishes();
        } finally {
            // 与套餐管理接口采用相同的缓存区域，覆盖关联套餐所在的所有分类。
            if (event.isSetmealsChanged()) {
                try {
                    clear("setmealCache");
                } finally {
                    clear("userSetmealCache");
                }
            }
        }
    }

    private void clear(String name) {
        Cache cache = cacheManager.getCache(name);
        if (cache != null) {
            cache.clear();
        }
    }
}
