package com.sky.config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** A production/default-profile process must fail before accepting weak authentication configuration. */
@Component
public class ProductionSecurityGuard implements InitializingBean {
    private final Environment environment;
    public ProductionSecurityGuard(Environment environment) { this.environment = environment; }
    @Override public void afterPropertiesSet() {
        var profiles = Arrays.asList(environment.getActiveProfiles());
        if (!profiles.contains("prod") && (profiles.contains("dev") || profiles.contains("test"))) return;
        String admin = requireSecret("sky.jwt.admin-secret-key"), user = requireSecret("sky.jwt.user-secret-key");
        if (admin.equals(user)) throw new IllegalStateException("Administrator and user JWT secrets must differ");
        requireSecret("sky.internal.token");
        String initialPassword = environment.getProperty("sky.employee.initial-password", "");
        if (!initialPassword.isBlank() && initialPassword.length() < 12)
            throw new IllegalStateException("Production employee initial password must have at least 12 characters");
        for (String key : new String[]{"sky.wechat.mock-login-enabled", "sky.payment.mock-enabled", "sky.security.docs-enabled", "sky.gateway.auth.docs-enabled"}) {
            if (environment.getProperty(key, Boolean.class, false)) throw new IllegalStateException("Production forbids " + key);
        }
        if (environment.getProperty("sky.security.cors-origins", "").contains("*"))
            throw new IllegalStateException("Production CORS requires explicit origins");
        if (!environment.getProperty("sky.wechat.api-base-url", "https://api.mch.weixin.qq.com").equals("https://api.mch.weixin.qq.com"))
            throw new IllegalStateException("Production forbids a replacement payment endpoint");
    }
    private String requireSecret(String key) {
        String value = environment.getProperty(key, "");
        if (value.getBytes(StandardCharsets.UTF_8).length < 32 || value.chars().distinct().count() < 12
                || value.startsWith("dev-only") || value.toLowerCase(java.util.Locale.ROOT).contains("change-me"))
            throw new IllegalStateException("Missing or weak production secret: " + key);
        return value;
    }
}
