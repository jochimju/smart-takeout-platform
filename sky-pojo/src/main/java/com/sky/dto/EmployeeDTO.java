package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class EmployeeDTO implements Serializable {

    private Long id; //这些属性名对应前端传送过来的属性

    private String username;

    private String name;

    private String phone;

    private String sex;

    private String idNumber;

}
