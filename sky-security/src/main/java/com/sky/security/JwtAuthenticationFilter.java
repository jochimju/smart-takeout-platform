package com.sky.security;

import com.sky.constant.JwtClaimsConstant;
import com.sky.context.BaseContext;
import com.sky.utils.JwtUtil;
import com.sky.utils.properties.JwtProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtProperties jwt;
    private final AccountAccess accounts;
    private final String internalToken;
    private final boolean docs;

    public JwtAuthenticationFilter(JwtProperties jwt, AccountAccess accounts, String internalToken, boolean docs) {
        this.jwt = jwt; this.accounts = accounts; this.internalToken = internalToken; this.docs = docs;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        BaseContext.removeCurrentId();
        try {
            if (!SecurityRoutes.publicRequest(request, docs)) {
                String path = request.getRequestURI();
                if (path.startsWith("/internal/") || path.startsWith("/actuator/")) {
                    String given = request.getHeader("X-Internal-Token");
                    if (internalToken == null || internalToken.isBlank() || given == null || !MessageDigest.isEqual(
                            internalToken.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
                        error(response, 403, "internal authentication required"); return;
                    }
                    authenticate("internal", "INTERNAL");
                } else if (path.startsWith("/admin/") || path.startsWith("/user/")) {
                    boolean admin = path.startsWith("/admin/");
                    try {
                        String token = request.getHeader(admin ? jwt.getAdminTokenName() : jwt.getUserTokenName());
                        var claims = JwtUtil.parseJWT(admin ? jwt.getAdminSecretKey() : jwt.getUserSecretKey(), token);
                        Long id = Long.valueOf(claims.get(admin ? JwtClaimsConstant.EMP_ID : JwtClaimsConstant.USER_ID).toString());
                        if (id <= 0 || !accounts.valid(token, admin)) throw new IllegalArgumentException("inactive session");
                        authenticate(id, admin ? "ADMIN" : "USER");
                        BaseContext.setCurrentId(id);
                    } catch (RuntimeException ex) {
                        error(response, 401, "invalid or expired token"); return;
                    }
                }
            }
            chain.doFilter(request, response);
        } finally {
            BaseContext.removeCurrentId();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(Object principal, String role) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(context);
    }

    public static void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":0,\"msg\":\"" + message + "\",\"data\":null}");
    }
}
