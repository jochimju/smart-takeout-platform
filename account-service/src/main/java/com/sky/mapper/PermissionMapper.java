package com.sky.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PermissionMapper {
    record Route(long id, String path) { }

    @Select("select id, path from permission where method = #{method} and type = 'API' and path is not null")
    java.util.List<Route> routes(String method);

    @Select("select count(1) from employee_role er join employee e on e.id = er.employee_id " +
            "join role r on r.id = er.role_id join role_permission rp on rp.role_id = r.id " +
            "where er.employee_id = #{employeeId} and rp.permission_id = #{permissionId} and e.status = 1 and r.status = 1")
    int granted(@Param("employeeId") Long employeeId, @Param("permissionId") Long permissionId);

    @Select("select count(1) from permission where method = #{method} and path = #{path}")
    int countPermission(@Param("method") String method, @Param("path") String path);

    @Select("select count(1) from employee_role er " +
            "join role_permission rp on er.role_id = rp.role_id " +
            "join permission p on rp.permission_id = p.id " +
            "where er.employee_id = #{employeeId} and p.method = #{method} and p.path = #{path}")
    int countEmployeePermission(@Param("employeeId") Long employeeId, @Param("method") String method, @Param("path") String path);
}
