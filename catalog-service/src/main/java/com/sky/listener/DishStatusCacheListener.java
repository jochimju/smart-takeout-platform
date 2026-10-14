package com.sky.listener;

import com.sky.event.DishStatusChangedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sky.cache.MenuCacheInvalidationOutbox;

@Component
public class DishStatusCacheListener {
    @Autowired
    private MenuCacheInvalidationOutbox invalidations;

    // Persist together with the business update; Redis delivery occurs after commit.
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void invalidate(DishStatusChangedEvent event) {
        invalidations.record("dish");
        if (event.isSetmealsChanged()) invalidations.record("setmeal");
    }
}
