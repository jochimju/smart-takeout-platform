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

    @Transactional(rollbackFor = Exception.class)
    public void deduct(List<ShoppingCart> items) {
        java.util.Map<String,ProductQuote> quotes = catalog.quotes(items);
        for (ShoppingCart item : items) {
            String type = item.getDishId() != null ? ProductQuote.DISH : ProductQuote.SETMEAL;
            Long id = item.getDishId() != null ? item.getDishId() : item.getSetmealId();
            ProductQuote quote = quotes.get(type + ':' + id);
            if (quote == null) throw new OrderBusinessException("商品不存在或已删除");
            jdbc.update("insert into trade_inventory(product_type,product_id,stock,catalog_version) values(?,?,?,?) on duplicate key update stock=if(values(catalog_version)>catalog_version,values(stock),stock),catalog_version=greatest(catalog_version,values(catalog_version))",
                    type, id, quote.getStock() == null ? 0 : quote.getStock(), quote.getVersion() == null ? 0 : quote.getVersion());
            int changed = jdbc.update("update trade_inventory set stock=stock-?,version=version+1 where product_type=? and product_id=? and stock>=?",
                    item.getNumber(), type, id, item.getNumber());
            if (changed != 1) throw new OrderBusinessException("商品库存不足");
        }
    }

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
