package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 餐厅（食堂）主数据。拆分后归商品服务所有：category / dish / setmeal 都通过 canteen_id 归属餐厅。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Canteen implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 所属校区 id */
    private Long campusZoneId;

    /** 餐厅编码，全局唯一 */
    private String code;

    /** 餐厅名称，同一校区内唯一 */
    private String name;

    /** 位置描述 */
    private String location;

    /** 简介 */
    private String description;

    /** 用餐时段：BREAKFAST / LUNCH / DINNER / ALL */
    private String mealPeriod;

    /** 状态 0 停用 1 启用 */
    private Integer status;

    /** 排序 */
    private Integer sort;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
