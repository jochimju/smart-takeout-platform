package com.sky.vo;

import lombok.Data;

import java.io.Serializable;

/** Client-facing restaurant view, backed by the existing canteen table. */
@Data
public class RestaurantVO implements Serializable {
    private Long id;
    private String code;
    private String name;
    private String location;
    private String description;
    private String businessStatus;
    private String mealPeriod;
    private String mealPeriodText;
    private Integer sort;
}
