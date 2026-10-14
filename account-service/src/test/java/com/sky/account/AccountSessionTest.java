package com.sky.account;

import com.sky.entity.Employee;
import com.sky.entity.User;
import com.sky.mapper.*;
import com.sky.utils.JwtUtil;
import com.sky.utils.properties.JwtProperties;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AccountSessionTest {
    @Test void disableVersionChangeAndLogoutInvalidateSessions() {
        var employees = mock(EmployeeMapper.class); var users = mock(UserMapper.class);
        var permissions = mock(PermissionAccess.class); var sessions = mock(SessionMapper.class);
        var jwt = new JwtProperties(); jwt.setAdminSecretKey("test-admin"); jwt.setUserSecretKey("test-user");
        var access = new LocalAccountAccess(employees, users, permissions, sessions, jwt);
        var employee = Employee.builder().id(1L).status(1).build();
        when(employees.getById(1L)).thenReturn(employee); when(sessions.version(1L)).thenReturn(0L);
        String token = JwtUtil.createJWT("test-admin", 60000, Map.of("empId", 1L, "av", 0));
        assertTrue(access.valid(token, true));
        employee.setStatus(0); assertFalse(access.valid(token, true));
        employee.setStatus(1); when(sessions.version(1L)).thenReturn(1L); assertFalse(access.valid(token, true));
        when(sessions.version(1L)).thenReturn(0L); when(sessions.revoked(anyString())).thenReturn(1);
        assertFalse(access.valid(token, true));
        var sessionService = new SessionService(sessions, jwt); sessionService.revoke(token, true);
        var claims = JwtUtil.parseJWT("test-admin", token); verify(sessions).revoke(claims.getId(), claims.getExpiration());
        assertFalse(access.valid(token, false));
    }
    @Test void userMustExistAndDatabaseFailureNeverAuthenticates() {
        var employees = mock(EmployeeMapper.class); var users = mock(UserMapper.class);
        var sessions = mock(SessionMapper.class); var jwt = new JwtProperties(); jwt.setUserSecretKey("test-user");
        var access = new LocalAccountAccess(employees, users, mock(PermissionAccess.class), sessions, jwt);
        String token = JwtUtil.createJWT("test-user", 60000, Map.of("userId", 9L));
        assertFalse(access.valid(token, false)); when(users.getById(9L)).thenReturn(User.builder().id(9L).build());
        assertTrue(access.valid(token, false)); when(sessions.revoked(anyString())).thenThrow(new IllegalStateException("down"));
        assertFalse(access.valid(token, false));
    }
}
