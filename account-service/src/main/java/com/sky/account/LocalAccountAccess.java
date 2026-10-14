package com.sky.account;

import com.sky.mapper.EmployeeMapper;
import com.sky.mapper.PermissionMapper;
import com.sky.mapper.UserMapper;
import com.sky.security.AccountAccess;
import com.sky.utils.JwtUtil;
import com.sky.utils.properties.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LocalAccountAccess implements AccountAccess {
    private final EmployeeMapper employees;
    private final UserMapper users;
    private final PermissionAccess permissions;
    private final SessionMapper sessions;
    private final JwtProperties jwt;

    @Override public boolean valid(String token, boolean admin) {
        try {
            var claims = JwtUtil.parseJWT(admin ? jwt.getAdminSecretKey() : jwt.getUserSecretKey(), token);
            if (claims.getId() == null || sessions.revoked(claims.getId()) > 0) return false;
            Long id = Long.valueOf(claims.get(admin ? "empId" : "userId").toString());
            if (!admin) return users.getById(id) != null;
            var employee = employees.getById(id);
            return employee != null && Integer.valueOf(1).equals(employee.getStatus())
                    && claims.get("av") != null
                    && Long.valueOf(claims.get("av").toString()).equals(sessions.version(id));
        } catch (RuntimeException ex) { return false; }
    }

    @Override public boolean permitted(Long employeeId, String method, String path) {
        return permissions.permitted(employeeId, method, path);
    }
}
