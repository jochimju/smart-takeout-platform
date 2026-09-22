package com.sky.gateway;

import com.sky.constant.JwtClaimsConstant;
import com.sky.utils.JwtUtil;
import com.sky.utils.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * First security boundary for public gateway traffic. Downstream services still
 * validate the original JWT and apply their own permission checks.
 */
@Component
@RequiredArgsConstructor
public class GatewayAuthenticationFilter implements GlobalFilter, Ordered {
    static final String AUTHENTICATED_ID = "X-Authenticated-Id";
    static final String AUTHENTICATED_ROLE = "X-Authenticated-Role";

    private final JwtProperties jwt;
    private final GatewayAuthProperties properties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS || isPublic(path)) {
            return chain.filter(exchange);
        }
        if (path.startsWith("/internal/")) {
            return reject(exchange, HttpStatus.FORBIDDEN, "internal endpoints are not exposed by the gateway");
        }

        Principal principal;
        if (path.startsWith("/admin/")) {
            principal = authenticate(exchange, jwt.getAdminTokenName(), jwt.getAdminSecretKey(), JwtClaimsConstant.EMP_ID,
                    "ADMIN", false);
        } else if (path.startsWith("/user/") || path.startsWith("/ws/")) {
            principal = authenticate(exchange, jwt.getUserTokenName(), jwt.getUserSecretKey(), JwtClaimsConstant.USER_ID,
                    "USER", path.startsWith("/ws/"));
        } else {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "authentication is required");
        }

        if (principal == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "invalid or expired token");
        }
        ServerWebExchange authenticated = exchange.mutate().request(builder -> builder.headers(headers -> {
            // Never propagate client-spoofed gateway identity headers.
            headers.remove(AUTHENTICATED_ID);
            headers.remove(AUTHENTICATED_ROLE);
            headers.set(AUTHENTICATED_ID, principal.id());
            headers.set(AUTHENTICATED_ROLE, principal.role());
        })).build();
        return chain.filter(authenticated);
    }

    private Principal authenticate(ServerWebExchange exchange, String header, String secret, String claim,
                                   String role, boolean websocket) {
        String token = exchange.getRequest().getHeaders().getFirst(header);
        if ((token == null || token.isBlank()) && websocket && properties.isAllowWebsocketQueryToken()) {
            token = exchange.getRequest().getQueryParams().getFirst(header);
        }
        if (token == null || token.isBlank()) return null;
        try {
            Claims claims = JwtUtil.parseJWT(secret, token);
            Object id = claims.get(claim);
            return id == null ? null : new Principal(String.valueOf(id), role);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean isPublic(String path) {
        return List.of(
                "/admin/employee/login", "/user/user/login", "/user/shop/status", "/user/seckill/activity/list",
                "/notify/paySuccess", "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
                "/actuator/prometheus", "/doc.html", "/favicon.ico", "/v3/api-docs"
        ).contains(path)
                || path.startsWith("/webjars/")
                || path.startsWith("/v3/api-docs/")
                || path.startsWith("/swagger-ui/")
                || path.startsWith("/dishes/")
                || path.startsWith("/setmeals/");
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":0,\"msg\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        // TraceIdFilter runs first and strips client supplied internal-token headers.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private record Principal(String id, String role) { }
}
