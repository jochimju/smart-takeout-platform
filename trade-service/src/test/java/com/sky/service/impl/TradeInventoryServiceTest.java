package com.sky.service.impl;

import com.sky.contract.catalog.ProductQuote;
import com.sky.entity.OrderDetail;
import com.sky.entity.ShoppingCart;
import com.sky.exception.OrderBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TradeInventoryServiceTest {
    @Test
    void conditionalUpdateRejectsInsufficientStockInsteadOfGoingNegative() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CatalogQuoteService catalog = mock(CatalogQuoteService.class);
        ProductQuote quote = ProductQuote.builder().productType(ProductQuote.DISH).productId(1L)
                .stock(1).price(BigDecimal.ONE).build();
        when(catalog.quotes(anyList())).thenReturn(Map.of("DISH:1", quote));
        when(jdbc.update(startsWith("insert into trade_inventory"), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.update(startsWith("update trade_inventory set stock=stock-"), any(), any(), any(), any())).thenReturn(0);
        ShoppingCart item = ShoppingCart.builder().dishId(1L).number(2).build();

        assertThrows(OrderBusinessException.class, () -> new TradeInventoryService(jdbc, catalog).deduct(List.of(item)));
        verify(jdbc).update(contains("stock>=?"), eq(2), eq(ProductQuote.DISH), eq(1L), eq(2));
    }

    @Test
    void rollbackFailsLoudlyWhenInventoryRecordIsMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("update trade_inventory set stock=stock+"), any(), any(), any())).thenReturn(0);
        OrderDetail line = OrderDetail.builder().dishId(1L).number(1).build();

        assertThrows(OrderBusinessException.class, () -> new TradeInventoryService(jdbc, mock(CatalogQuoteService.class)).rollback(List.of(line)));
    }
}
