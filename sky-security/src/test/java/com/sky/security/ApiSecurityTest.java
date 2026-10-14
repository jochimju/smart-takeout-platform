package com.sky.security;

import com.sky.context.BaseContext;
import com.sky.utils.JwtUtil;
import com.sky.utils.properties.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ApiSecurityTest.App.class, properties = {
        "sky.internal.token=test-internal-secret", "sky.security.cors-origins=http://localhost:5173"})
@AutoConfigureMockMvc
class ApiSecurityTest {
    @SpringBootConfiguration @EnableAutoConfiguration @Import({ApiSecurityConfiguration.class, Endpoints.class})
    static class App {
        @Bean AccountAccess accounts() { return mock(AccountAccess.class); }
        @Bean JwtProperties jwt() {
            var jwt = new JwtProperties(); jwt.setAdminSecretKey("admin-secret-for-test"); jwt.setUserSecretKey("user-secret-for-test");
            jwt.setAdminTokenName("token"); jwt.setUserTokenName("authentication"); return jwt;
        }
    }
    @RestController static class Endpoints {
        @PostMapping({"/admin/employee/login", "/user/user/login", "/notify/paySuccess"}) String publicPost() { return "ok"; }
        @PostMapping("/user/order/submit") Long submit() { return BaseContext.getCurrentId(); }
        @GetMapping("/admin/dish/{id}") Long dish() { return BaseContext.getCurrentId(); }
        @PostMapping("/admin/employee/logout") String logout() { return "ok"; }
        @PostMapping("/internal/catalog/quotes") String internal() { return "ok"; }
        @GetMapping("/unexpected") String unexpected() { return "bad"; }
    }
    @Autowired MockMvc mvc; @Autowired AccountAccess accounts; @Autowired JwtProperties jwt;
    @BeforeEach void resetMocks() {
        reset(accounts); when(accounts.valid(anyString(), anyBoolean())).thenReturn(true);
    }
    String admin(long ttl) { return JwtUtil.createJWT(jwt.getAdminSecretKey(), ttl, Map.of("empId", 7L, "av", 0)); }
    String user(long ttl) { return JwtUtil.createJWT(jwt.getUserSecretKey(), ttl, Map.of("userId", 9L)); }

    @Test void loginAndCallbackKeepAnonymousPostContract() throws Exception {
        for (String path : new String[]{"/admin/employee/login", "/user/user/login", "/notify/paySuccess"})
            mvc.perform(post(path)).andExpect(status().isOk());
        verifyNoInteractions(accounts);
    }
    @Test void userSubmissionRetainsHeaderAndIdentityAndClearsThread() throws Exception {
        mvc.perform(post("/user/order/submit").header("authentication", user(60000))).andExpect(status().isOk()).andExpect(content().string("9"));
        assertNull(BaseContext.getCurrentId());
        mvc.perform(post("/user/order/submit")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(0));
    }
    @Test void badSignatureExpiredAndWrongRealmFail() throws Exception {
        mvc.perform(get("/admin/dish/12").header("token", user(60000))).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/dish/12").header("token", admin(-1000))).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/dish/12").header("token", "forged")).andExpect(status().isUnauthorized());
    }
    @Test void adminPermissionMustBeGrantedAndUnavailableFailsClosed() throws Exception {
        mvc.perform(get("/admin/dish/12").header("token", admin(60000))).andExpect(status().isForbidden());
        when(accounts.permitted(7L, "GET", "/admin/dish/12")).thenReturn(true);
        mvc.perform(get("/admin/dish/12").header("token", admin(60000))).andExpect(status().isOk()).andExpect(content().string("7"));
        when(accounts.permitted(anyLong(), anyString(), anyString())).thenThrow(new IllegalStateException("down"));
        mvc.perform(get("/admin/dish/12").header("token", admin(60000))).andExpect(status().isForbidden());
    }
    @Test void inactiveOrRevokedSessionCannotAccessOrLogout() throws Exception {
        when(accounts.valid(anyString(), anyBoolean())).thenReturn(false);
        mvc.perform(post("/admin/employee/logout").header("token", admin(60000))).andExpect(status().isUnauthorized());
    }
    @Test void internalAndUnknownRoutesAreProtected() throws Exception {
        mvc.perform(post("/internal/catalog/quotes")).andExpect(status().isForbidden());
        mvc.perform(post("/internal/catalog/quotes").header("X-Internal-Token", "test-internal-secret")).andExpect(status().isOk());
        mvc.perform(get("/unexpected")).andExpect(status().isUnauthorized());
    }
    @Test void configuredCorsPreflightWorksAndForeignOriginFails() throws Exception {
        mvc.perform(options("/user/order/submit").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authentication"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/user/order/submit").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }
}
