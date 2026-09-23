package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 濂楅
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Setmeal implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    //鍒嗙被id
    private Long categoryId;

    //濂楅鍚嶇О
    private String name;

    //濂楅浠锋牸
    private BigDecimal price;

    //鐘舵€?0:鍋滅敤 1:鍚敤
    private Integer status;

    private Integer stock;

    //鎻忚堪淇℃伅
    private String description;

    //鍥剧墖
    private String image;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long createUser;

    private Long updateUser;

    //所属餐厅（食堂）id
    private Long canteenId;
}
