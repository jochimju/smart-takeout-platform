package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/** 管理端修改当前登录员工的密码。 */
@Data
public class EmployeePasswordDTO implements Serializable {

    private String oldPassword;

    private String newPassword;
}
