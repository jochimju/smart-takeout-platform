package com.sky.security;

import com.sky.config.ProductionSecurityGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ProductionSecurityGuardTest {
    private MockEnvironment secure() {
        return new MockEnvironment().withProperty("sky.jwt.admin-secret-key", "aG9bZ2kL8nH1qW7sD3mF6uV0jC4pR5tE")
                .withProperty("sky.jwt.user-secret-key", "xQ3mB7wH0vL9cF1jT5pD8sN2kR6uZ4gA")
                .withProperty("sky.internal.token", "rT4vX8jD2nF6qH0mZ5sB9wK1cP3uL7gE");
    }
    @Test void productionFailsOnMissingWeakSameSecretsAndMocks() {
        assertThrows(IllegalStateException.class, () -> new ProductionSecurityGuard(new MockEnvironment()).afterPropertiesSet());
        var env = secure(); env.setActiveProfiles("prod");
        assertDoesNotThrow(() -> new ProductionSecurityGuard(env).afterPropertiesSet());
        env.setProperty("sky.wechat.mock-login-enabled", "true");
        assertThrows(IllegalStateException.class, () -> new ProductionSecurityGuard(env).afterPropertiesSet());
        env.setActiveProfiles("dev", "prod");
        assertThrows(IllegalStateException.class, () -> new ProductionSecurityGuard(env).afterPropertiesSet());
        env.setProperty("sky.wechat.mock-login-enabled", "false"); env.setProperty("sky.internal.token", "dev-only-change-me");
        assertThrows(IllegalStateException.class, () -> new ProductionSecurityGuard(env).afterPropertiesSet());
    }
    @Test void developmentRemainsExplicitlyAvailable() {
        var env = new MockEnvironment(); env.setActiveProfiles("dev");
        assertDoesNotThrow(() -> new ProductionSecurityGuard(env).afterPropertiesSet());
    }
}
