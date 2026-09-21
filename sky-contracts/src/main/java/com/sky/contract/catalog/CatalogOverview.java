package com.sky.contract.catalog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CatalogOverview {
    private Integer soldDishes;
    private Integer discontinuedDishes;
    private Integer soldSetmeals;
    private Integer discontinuedSetmeals;
}
