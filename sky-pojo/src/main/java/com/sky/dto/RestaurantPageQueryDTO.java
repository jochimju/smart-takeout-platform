package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/** 管理端餐厅分页筛选。 */
@Data
public class RestaurantPageQueryDTO implements Serializable {
    private int page = 1;
    private int pageSize = 10;
    private String name;
    private Integer status;
    private Long campusZoneId;
}
