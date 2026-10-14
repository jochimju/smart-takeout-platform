package com.sky.service.impl;

import com.sky.client.CatalogClient;
import com.sky.contract.catalog.ProductQuote;
import com.sky.contract.catalog.ProductQuoteRequest;
import com.sky.entity.ShoppingCart;
import com.sky.exception.OrderBusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CatalogQuoteService {
    private final CatalogClient catalogClient;

    /** 按菜品/套餐 ID 批量请求 catalog-service；返回值以商品类型和 ID 组成键。 */
    public Map<String, ProductQuote> quotes(List<ShoppingCart> items) {
        List<Long> dishes = items.stream().map(ShoppingCart::getDishId).filter(java.util.Objects::nonNull).distinct().toList();
        List<Long> setmeals = items.stream().map(ShoppingCart::getSetmealId).filter(java.util.Objects::nonNull).distinct().toList();
        try {
            List<ProductQuote> quotes = catalogClient.quotes(ProductQuoteRequest.builder().dishIds(dishes).setmealIds(setmeals).build());
            return (quotes == null ? Collections.<ProductQuote>emptyList() : quotes).stream()
                    .collect(Collectors.toMap(q -> key(q.getProductType(), q.getProductId()), q -> q));
        } catch (RuntimeException ex) {
            throw new com.sky.exception.RemoteServiceUnavailableException("商品服务暂时不可用，请稍后重试");
        }
    }

    public ProductQuote quoteDish(Long id) { return require(ProductQuote.DISH, id, quotes(List.of(ShoppingCart.builder().dishId(id).build()))); }
    public ProductQuote quoteSetmeal(Long id) { return require(ProductQuote.SETMEAL, id, quotes(List.of(ShoppingCart.builder().setmealId(id).build()))); }

    /** 用服务端商品报价刷新内存中的购物车对象；不在这里更新购物车表。 */
    public void refresh(List<ShoppingCart> items) {
        Map<String, ProductQuote> result = quotes(items);
        refresh(items,result);
    }
    public void refresh(List<ShoppingCart> items,Map<String,ProductQuote> result) {
        for (ShoppingCart item : items) {
            String type = item.getDishId() != null ? ProductQuote.DISH : ProductQuote.SETMEAL;
            Long id = item.getDishId() != null ? item.getDishId() : item.getSetmealId();
            ProductQuote quote = require(type, id, result);
            if (!Integer.valueOf(1).equals(quote.getStatus())) throw new OrderBusinessException("商品已停售，请刷新后重试");
            item.setName(quote.getName()); item.setImage(quote.getImage()); item.setAmount(quote.getPrice());
        }
    }

    private ProductQuote require(String type, Long id, Map<String, ProductQuote> values) {
        ProductQuote quote = values.get(key(type, id));
        if (quote == null) throw new OrderBusinessException("商品不存在或已删除");
        return quote;
    }
    private static String key(String type, Long id) { return type + ':' + id; }
}
