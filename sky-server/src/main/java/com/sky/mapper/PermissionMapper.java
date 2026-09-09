package com.sky.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PermissionMapper {

    @Select("select count(1) from permission where method = #{method} and path = #{path}")
    int countPermission(@Param("method") String method, @Param("path") String path);

    @Select("select count(1) from employee_role er " +
            "join role_permission rp on er.role_id = rp.role_id " +
            "join permission p on rp.permission_id = p.id " +
            "where er.employee_id = #{employeeId} and p.method = #{method} and p.path = #{path}")
    int countEmployeePermission(@Param("employeeId") Long employeeId, @Param("method") String method, @Param("path") String path);
}
