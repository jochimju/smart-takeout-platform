package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 餐厅新增 / 编辑参数。
 */
@Data
public class RestaurantDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long campusZoneId;

    private String code;

    private String name;

    private String location;

    private String description;

    /** BREAKFAST / LUNCH / DINNER / ALL */
    private String mealPeriod;

    private Integer sort;
}
