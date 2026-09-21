package com.sky.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 校区视图对象，供餐厅维护下拉使用。
 */
@Data
public class CampusZoneVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String code;

    private String name;
}
