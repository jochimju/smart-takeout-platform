package com.sky.contract.catalog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductQuoteRequest {
    private List<Long> dishIds;
    private List<Long> setmealIds;
}
