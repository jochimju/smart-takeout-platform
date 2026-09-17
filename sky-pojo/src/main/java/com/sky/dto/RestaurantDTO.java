package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/** 管理端新增、编辑餐厅请求。 */
@Data
public class RestaurantDTO implements Serializable {
    private Long id;
    private Long campusZoneId;
    private String code;
    private String name;
    private String location;
    private String description;
    private String mealPeriod;
    private Integer sort;
}
