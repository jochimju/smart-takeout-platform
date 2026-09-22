package com.sky.gateway;

import com.sky.constant.JwtClaimsConstant;
import com.sky.utils.JwtUtil;
import com.sky.utils.properties.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayAuthenticationFilterTest {
    private final JwtProperties jwt = properties();
    private final GatewayAuthenticationFilter filter = new GatewayAuthenticationFilter(jwt, new GatewayAuthProperties());

    @Test
    void rejectsAdminPathWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/order/page").build());

        filter.filter(exchange, passingChain()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsUserTokenOnAdminPath() {
        String userToken = JwtUtil.createJWT(jwt.getUserSecretKey(), 60_000, Map.of(JwtClaimsConstant.USER_ID, 9L));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/order/page")
                .header(jwt.getUserTokenName(), userToken).build());

        filter.filter(exchange, passingChain()).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void forwardsVerifiedAdminIdentityAndOverwritesSpoofedHeaders() {
        String adminToken = JwtUtil.createJWT(jwt.getAdminSecretKey(), 60_000, Map.of(JwtClaimsConstant.EMP_ID, 7L));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/admin/order/page")
                .header(jwt.getAdminTokenName(), adminToken)
                .header(GatewayAuthenticationFilter.AUTHENTICATED_ID, "spoofed").build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = next -> {
            forwarded.set(next);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertNotNull(forwarded.get());
        assertEquals("7", forwarded.get().getRequest().getHeaders().getFirst(GatewayAuthenticationFilter.AUTHENTICATED_ID));
        assertEquals("ADMIN", forwarded.get().getRequest().getHeaders().getFirst(GatewayAuthenticationFilter.AUTHENTICATED_ROLE));
    }

    @Test
    void allowsLoginWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/user/user/login").build());
        AtomicReference<Boolean> called = new AtomicReference<>(false);

        filter.filter(exchange, next -> {
            called.set(true);
            return Mono.empty();
        }).block();

        assertTrue(Boolean.TRUE.equals(called.get()));
    }

    private GatewayFilterChain passingChain() {
        return exchange -> Mono.empty();
    }

    private JwtProperties properties() {
        JwtProperties value = new JwtProperties();
        value.setAdminSecretKey("admin-secret-for-test");
        value.setAdminTokenName("token");
        value.setUserSecretKey("user-secret-for-test");
        value.setUserTokenName("authentication");
        return value;
    }
}
