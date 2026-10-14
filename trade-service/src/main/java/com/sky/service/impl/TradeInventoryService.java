package com.sky.service.impl;

import com.sky.contract.catalog.ProductQuote;
import com.sky.entity.OrderDetail;
import com.sky.entity.ShoppingCart;
import com.sky.exception.OrderBusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TradeInventoryService {
    private final JdbcTemplate jdbc;
    private final CatalogQuoteService catalog;

    /** 加入建单本地事务：查商品库存基础信息，再条件扣减交易库存，失败回滚。 */
    @Transactional(rollbackFor = Exception.class)
    public void deduct(List<ShoppingCart> items) {
        java.util.Map<String,ProductQuote> quotes = catalog.quotes(items);
        deductQuoted(items,quotes);
    }
    @Transactional(rollbackFor=Exception.class)
    public void deductQuoted(List<ShoppingCart> items,java.util.Map<String,ProductQuote> quotes) {
        for (ShoppingCart item : items.stream().sorted(java.util.Comparator.comparing(i->(i.getDishId()!=null?"DISH:"+i.getDishId():"SETMEAL:"+i.getSetmealId()))).toList()) {
            if(item.getNumber()==null || item.getNumber()<=0)throw new OrderBusinessException("库存数量无效");
            String type = item.getDishId() != null ? ProductQuote.DISH : ProductQuote.SETMEAL;
            Long id = item.getDishId() != null ? item.getDishId() : item.getSetmealId();
            ProductQuote quote = quotes.get(type + ':' + id);
            if (quote == null) throw new OrderBusinessException("商品不存在或已删除");
            // Catalog metadata changes must never replenish an already established balance.
            jdbc.update("insert into trade_inventory(product_type,product_id,stock,catalog_version) values(?,?,?,?) on duplicate key update catalog_version=greatest(catalog_version,values(catalog_version))",
                    type, id, quote.getStock() == null ? 0 : quote.getStock(), quote.getVersion() == null ? 0 : quote.getVersion());
            // stock>=数量与扣减在同一条 SQL 中完成，避免先查询再扣导致并发超卖。
            int changed = jdbc.update("update trade_inventory set stock=stock-?,version=version+1 where product_type=? and product_id=? and stock>=?",
                    item.getNumber(), type, id, item.getNumber());
            if (changed != 1) throw new OrderBusinessException("商品库存不足");
        }
    }

    /** Delta changes are recorded atomically and cannot be applied twice. Caller supplies a stable ID. */
    @Transactional(rollbackFor = Exception.class)
    public void adjust(String adjustmentId, String type, Long productId, int delta) {
        if(adjustmentId==null || !adjustmentId.matches("[A-Za-z0-9_-]{16,64}") || productId==null ||
                !(ProductQuote.DISH.equals(type)||ProductQuote.SETMEAL.equals(type)) || delta==0)
            throw new OrderBusinessException("库存调整参数无效");
        int inserted=jdbc.update("insert ignore into trade_inventory_adjustment(adjustment_id,product_type,product_id,delta) values(?,?,?,?)",
                adjustmentId,type,productId,delta);
        if(inserted==0) {
            var prior=jdbc.queryForMap("select product_type,product_id,delta from trade_inventory_adjustment where adjustment_id=? lock in share mode",adjustmentId);
            if(!type.equals(prior.get("product_type")) || productId.longValue()!=((Number)prior.get("product_id")).longValue() || delta!=((Number)prior.get("delta")).intValue())
                throw new OrderBusinessException("库存调整编号已用于其他参数");
            return;
        }
        if(jdbc.update("update trade_inventory set stock=stock+?,version=version+1 where product_type=? and product_id=? and stock+?>=0 and stock+?<=2147483647",
                delta,type,productId,delta,delta)!=1) throw new OrderBusinessException("库存未建账或调整后余额不足");
    }

    /** New activities receive inventory from the normal sellable pool exactly once. */
    @Transactional(rollbackFor = Exception.class)
    public void allocate(Long activityId, Long setmealId, int quantity) {
        var quote=catalog.quoteSetmeal(setmealId);
        jdbc.update("insert into trade_inventory(product_type,product_id,stock,catalog_version) values(?,?,?,?) on duplicate key update product_id=product_id",
                ProductQuote.SETMEAL,setmealId,quote.getStock()==null?0:quote.getStock(),quote.getVersion()==null?0:quote.getVersion());
        int inserted=jdbc.update("insert ignore into seckill_inventory_allocation(activity_id,setmeal_id,quantity) values(?,?,?)",activityId,setmealId,quantity);
        if(inserted==0) {
            var old=jdbc.queryForMap("select setmeal_id,quantity from seckill_inventory_allocation where activity_id=? lock in share mode",activityId);
            if(setmealId.longValue()!=((Number)old.get("setmeal_id")).longValue() || quantity!=((Number)old.get("quantity")).intValue())
                throw new OrderBusinessException("秒杀库存划拨参数冲突");
            return;
        }
        if(quantity<0 || jdbc.update("update trade_inventory set stock=stock-?,version=version+1 where product_type=? and product_id=? and stock>=?",quantity,ProductQuote.SETMEAL,setmealId,quantity)!=1)
            throw new OrderBusinessException("普通套餐库存不足，不能划拨秒杀额度");
    }

    /** 从订单明细恢复库存；防止重复释放由上层订单锁和已取消状态检查负责。 */
    @Transactional(rollbackFor = Exception.class)
    public void rollback(List<OrderDetail> lines) {
        for (OrderDetail line : lines) {
            String type = line.getDishId() != null ? ProductQuote.DISH : ProductQuote.SETMEAL;
            Long id = line.getDishId() != null ? line.getDishId() : line.getSetmealId();
            int changed = jdbc.update("update trade_inventory set stock=stock+?,version=version+1 where product_type=? and product_id=?",
                    line.getNumber(), type, id);
            if (changed != 1) throw new OrderBusinessException("库存记录不存在");
        }
    }
}
