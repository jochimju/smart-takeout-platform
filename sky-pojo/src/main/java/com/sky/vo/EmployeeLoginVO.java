package com.sky.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel(description = "employee login response")
public class EmployeeLoginVO implements Serializable {

    @ApiModelProperty("id")
    private Long id;

    @ApiModelProperty("username")
    private String userName;

    @ApiModelProperty("name")
    private String name;

    @ApiModelProperty("jwt token")
    private String token;

}
