package com.sky.client;

import com.sky.contract.catalog.ProductQuote;
import com.sky.contract.catalog.ProductQuoteRequest;
import com.sky.contract.catalog.CatalogOverview;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@FeignClient(name = "catalog-service", configuration = AccountFeignConfiguration.class,
        fallbackFactory = CatalogClientFallbackFactory.class)
public interface CatalogClient {
    @PostMapping("/internal/catalog/quotes")
    List<ProductQuote> quotes(@RequestBody ProductQuoteRequest request);

    @GetMapping("/internal/catalog/overview")
    CatalogOverview overview();
}
