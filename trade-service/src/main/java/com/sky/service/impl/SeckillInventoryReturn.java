package com.sky.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.sky.exception.OrderBusinessException;

@Service
@RequiredArgsConstructor
public class SeckillInventoryReturn {
    private final JdbcTemplate jdbc;
    private final SeckillCache cache;

    @Transactional(rollbackFor = Exception.class)
    public int closeout(Long activityId) {
        var activity = jdbc.queryForMap("select * from seckill_activity where id=? for update", activityId);
        if (((Number) activity.get("status")).intValue() != 0)
            throw new OrderBusinessException("请先停用活动再回收额度");
        cache.pause(activityId);
        if (!cache.drained(activityId))
            throw new OrderBusinessException("秒杀预留或回补未排空，不能回收额度");
        var buckets = jdbc.queryForList("select remaining_stock from seckill_stock_bucket where activity_id=? order by bucket_id for update", activityId);
        var allocation = jdbc.queryForList("select * from seckill_inventory_allocation where activity_id=? for update", activityId);
        if (allocation.isEmpty()) throw new OrderBusinessException("历史活动无划拨账本，请人工核对");
        var row = allocation.get(0);
        if ("RETURNED".equals(row.get("state"))) return 0;
        if (jdbc.queryForObject("select count(*) from seckill_submit_request where activity_id=? and state='ACCEPTED'", Integer.class, activityId) > 0
                || jdbc.queryForObject("select count(*) from seckill_cache_event where activity_id=?", Integer.class, activityId) > 0)
            throw new OrderBusinessException("秒杀受理或回补未排空");
        int remaining = buckets.isEmpty() ? ((Number) activity.get("remaining_stock")).intValue()
                : buckets.stream().mapToInt(b -> ((Number) b.get("remaining_stock")).intValue()).sum();
        if (remaining < 0 || remaining > ((Number) row.get("quantity")).intValue()
                || jdbc.update("update trade_inventory set stock=stock+?,version=version+1 where product_type='SETMEAL' and product_id=?", remaining, row.get("setmeal_id")) != 1)
            throw new OrderBusinessException("划拨或普通库存账不一致");
        jdbc.update("update seckill_inventory_allocation set state='RETURNED',returned_quantity=? where activity_id=?", remaining, activityId);
        return remaining;
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelledAfterReturn(Long activityId) {
        var rows = jdbc.queryForList("select * from seckill_inventory_allocation force index(idx_allocation_return_state) where state='RETURNED' and activity_id=? for update", activityId);
        if (rows.isEmpty()) return;
        var row = rows.get(0);
        if (jdbc.update("update seckill_inventory_allocation set returned_quantity=returned_quantity+1 where activity_id=? and returned_quantity<quantity", activityId) != 1)
            throw new OrderBusinessException("划拨回收账本不一致");
        if (jdbc.update("update trade_inventory set stock=stock+1,version=version+1 where product_type='SETMEAL' and product_id=?", row.get("setmeal_id")) != 1)
            throw new OrderBusinessException("普通套餐库存账缺失");
    }
}
