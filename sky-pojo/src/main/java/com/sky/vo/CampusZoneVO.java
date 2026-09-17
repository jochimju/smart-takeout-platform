package com.sky.vo;

import lombok.Data;

import java.io.Serializable;

/** 管理端餐厅表单使用的校区选项。 */
@Data
public class CampusZoneVO implements Serializable {
    private Long id;
    private String code;
    private String name;
}
