package com.sky.websocket;

import com.sky.utils.JwtUtil;
import com.sky.utils.properties.JwtProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticate before upgrading the HTTP connection, including direct service access. */
@Component
@Order(-100)
public class WebSocketAuthenticationFilter extends OncePerRequestFilter {
    private final JwtProperties jwt;
    private final NotificationAccountAccess accounts;
    private final boolean query;
    public WebSocketAuthenticationFilter(JwtProperties jwt, NotificationAccountAccess accounts,
            @Value("${sky.websocket.allow-query-token:false}") boolean query) { this.jwt=jwt;this.accounts=accounts;this.query=query; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/ws/"); }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String token = req.getHeader(jwt.getAdminTokenName());
        if ((token == null || token.isBlank()) && query) token = req.getParameter(jwt.getAdminTokenName());
        long id;
        try { id = Long.parseLong(JwtUtil.parseJWT(jwt.getAdminSecretKey(), token).get("empId").toString()); }
        catch (RuntimeException ex) { res.setStatus(401); return; }
        if (!accounts.allowed(token, id, req.getRequestURI())) { res.setStatus(403); return; }
        chain.doFilter(req, res);
    }
}
