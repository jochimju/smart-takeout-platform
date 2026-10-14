package com.sky.account;

import com.sky.mapper.PermissionMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PermissionAccessTest {
    @Test void templatesMatchAndUnknownPathsDenyByDefault() {
        var mapper = mock(PermissionMapper.class); var access = new PermissionAccess(mapper);
        when(mapper.routes("GET")).thenReturn(List.of(new PermissionMapper.Route(1, "/admin/dish/{id}")));
        when(mapper.granted(7L, 1L)).thenReturn(1);
        assertTrue(access.permitted(7L, "GET", "/admin/dish/12"));
        assertTrue(access.permitted(7L, "HEAD", "/admin/dish/12"));
        assertFalse(access.permitted(7L, "GET", "/admin/dish/12/extra"));
        assertFalse(access.permitted(7L, "DELETE", "/admin/dish/12"));
        assertFalse(access.permitted(8L, "GET", "/admin/dish/12"));
    }
    @Test void broadGrantCannotOverrideSpecificRestriction() {
        var mapper = mock(PermissionMapper.class); var access = new PermissionAccess(mapper);
        when(mapper.routes("GET")).thenReturn(List.of(new PermissionMapper.Route(1, "/admin/dish/{id}"),
                new PermissionMapper.Route(2, "/admin/dish/page")));
        when(mapper.granted(7L, 1L)).thenReturn(1);
        assertFalse(access.permitted(7L, "GET", "/admin/dish/page"));
        verify(mapper).granted(7L, 2L);
    }
}
