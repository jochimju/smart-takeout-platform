package com.sky.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

public final class SecurityRoutes {
    private SecurityRoutes() { }
    public static boolean publicRequest(HttpServletRequest request, boolean docs) {
        String method = request.getMethod(), path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI();
        if ("OPTIONS".equals(method)) return true;
        if ("POST".equals(method) && Set.of("/admin/employee/login", "/user/user/login", "/notify/paySuccess").contains(path)) return true;
        if (!"GET".equals(method) && !"HEAD".equals(method)) return false;
        return Set.of("/user/shop/status", "/user/seckill/activity/list", "/actuator/health",
                "/actuator/health/liveness", "/actuator/health/readiness", "/favicon.ico").contains(path)
                || path.startsWith("/dishes/") || path.startsWith("/setmeals/")
                || (docs && (path.equals("/doc.html") || path.equals("/v3/api-docs")
                || path.startsWith("/webjars/") || path.startsWith("/v3/api-docs/") || path.startsWith("/swagger-ui/")));
    }
}
