package com.sky.websocket;

import com.sky.constant.JwtClaimsConstant;
import com.sky.utils.JwtUtil;
import com.sky.utils.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Re-validates user JWTs for direct notification-service WebSocket access. */
public class WebSocketAuthConfigurator extends ServerEndpointConfig.Configurator {
    static final String AUTHENTICATED_USER_ID = "authenticatedUserId";
    static final String AUTH_REJECTED = "authRejected";

    @Override
    public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request,
                                jakarta.websocket.HandshakeResponse response) {
        try {
            JwtProperties jwt = SpringContext.get().getBean(JwtProperties.class);
            String token = first(request.getHeaders().get(jwt.getUserTokenName()));
            if ((token == null || token.isBlank()) && allowQueryToken()) {
                token = queryValue(request.getQueryString(), jwt.getUserTokenName());
            }
            Claims claims = JwtUtil.parseJWT(jwt.getUserSecretKey(), token);
            Object userId = claims.get(JwtClaimsConstant.USER_ID);
            if (userId == null) throw new IllegalArgumentException("user claim missing");
            config.getUserProperties().put(AUTHENTICATED_USER_ID, String.valueOf(userId));
        } catch (RuntimeException ex) {
            config.getUserProperties().put(AUTH_REJECTED, Boolean.TRUE);
        }
    }

    private static boolean allowQueryToken() {
        return SpringContext.get().getEnvironment().getProperty("sky.websocket.allow-query-token", Boolean.class, false);
    }

    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String queryValue(String query, String key) {
        if (query == null || query.isBlank()) return null;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && key.equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
