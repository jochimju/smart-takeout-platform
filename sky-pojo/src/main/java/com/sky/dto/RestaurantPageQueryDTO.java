package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 餐厅分页查询参数。
 */
@Data
public class RestaurantPageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private int page = 1;

    private int pageSize = 10;

    /** 餐厅名称模糊匹配 */
    private String name;

    /** 状态 0 停用 1 启用，空表示不限 */
    private Integer status;

    /** 所属校区，空表示不限 */
    private Long campusZoneId;
}
