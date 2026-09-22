package com.sky.client;

import com.sky.contract.catalog.CatalogOverview;
import com.sky.contract.catalog.ProductQuote;
import com.sky.contract.catalog.ProductQuoteRequest;
import com.sky.exception.RemoteServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** Pricing and catalog summaries are never guessed when catalog-service is unhealthy. */
@Component
@Slf4j
public class CatalogClientFallbackFactory implements FallbackFactory<CatalogClient> {
    @Override
    public CatalogClient create(Throwable cause) {
        log.warn("catalog-service Feign fallback activated", cause);
        return new CatalogClient() {
            @Override public List<ProductQuote> quotes(ProductQuoteRequest request) { throw unavailable(); }
            @Override public CatalogOverview overview() { throw unavailable(); }
        };
    }

    private RemoteServiceUnavailableException unavailable() {
        return new RemoteServiceUnavailableException("菜单服务暂时不可用，请稍后重试");
    }
}
