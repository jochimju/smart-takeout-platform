package com.sky.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 餐厅视图对象。businessStatus 由每日档口状态聚合而成，mealPeriodText 是时段展示文案。
 */
@Data
public class RestaurantVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String code;

    private String name;

    private String location;

    private String description;

    /** OPEN / CLOSED */
    private String businessStatus;

    /** BREAKFAST / LUNCH / DINNER / ALL */
    private String mealPeriod;

    private String mealPeriodText;

    private Integer sort;
}
