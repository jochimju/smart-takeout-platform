package com.sky.contract.catalog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductQuote {
    public static final String DISH = "DISH";
    public static final String SETMEAL = "SETMEAL";
    private Long productId;
    private String productType;
    private String name;
    private String image;
    private BigDecimal price;
    private Integer status;
    private Integer stock;
    private Long version;
    /** 商品所属餐厅，交易侧据此按餐厅过滤秒杀等活动。 */
    private Long canteenId;
}
