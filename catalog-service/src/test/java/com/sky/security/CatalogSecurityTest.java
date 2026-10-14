package com.sky.security;

import com.sky.client.AccountClient;
import com.sky.config.RemoteAccountAccess;
import com.sky.service.DishService;
import com.sky.cache.MenuCache;
import com.sky.utils.JwtUtil;
import com.sky.utils.properties.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={com.sky.controller.admin.DishController.class, com.sky.controller.user.DishController.class}, properties={
        "spring.config.import=", "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "sky.jwt.admin-secret-key=test-admin", "sky.jwt.user-secret-key=test-user", "sky.internal.token=test-internal"})
@Import({ApiSecurityConfiguration.class, RemoteAccountAccess.class, JwtProperties.class}) @ActiveProfiles("test")
class CatalogSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccountClient accounts; @MockitoBean DishService dishes; @MockitoBean MenuCache cache;
    @Test void adminReadsNeedRemoteSessionAndPermission() throws Exception {
        String token=JwtUtil.createJWT("test-admin",60000,Map.of("empId",7L,"av",0));
        when(accounts.valid(any())).thenReturn(true);
        mvc.perform(get("/admin/dish/12").header("token",token)).andExpect(status().isForbidden());
        verifyNoInteractions(dishes);
        when(accounts.permitted(7L,"GET","/admin/dish/12")).thenReturn(true);
        mvc.perform(get("/admin/dish/12").header("token",token)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        verify(dishes).getByIdWithFlavor(12L);
    }
    @Test void userMenuKeepsAuthenticationHeaderAndRemoteOutageFailsClosed() throws Exception {
        String token=JwtUtil.createJWT("test-user",60000,Map.of("userId",9L));
        when(accounts.valid(any())).thenReturn(true); when(dishes.listWithFlavorCache(1L)).thenReturn(java.util.List.of());
        mvc.perform(get("/user/dish/list").param("categoryId","1").header("authentication",token)).andExpect(status().isOk());
        when(accounts.valid(any())).thenReturn(false);
        mvc.perform(get("/user/dish/list").header("authentication",token)).andExpect(status().isUnauthorized());
    }
    @Test void databaseFailureDoesNotBecomeAnAuthenticationError() throws Exception {
        String token=JwtUtil.createJWT("test-admin",60000,Map.of("empId",7L,"av",0));
        when(accounts.valid(any())).thenReturn(true);
        when(accounts.permitted(7L,"PUT","/admin/dish")).thenReturn(true);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
                .when(dishes).updateWithFlavor(any());
        mvc.perform(put("/admin/dish").header("token",token).contentType("application/json").content("{\"id\":1,\"price\":99}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(0));
    }
}
