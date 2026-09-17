package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 餐厅（数据库中的 canteen 表）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Canteen implements Serializable {
    private Long id;
    private Long campusZoneId;
    private String code;
    private String name;
    private String location;
    private String description;
    /** BREAKFAST, LUNCH, DINNER, or ALL (all three campus meal windows). */
    private String mealPeriod;
    /** 0 停用，1 启用。 */
    private Integer status;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
