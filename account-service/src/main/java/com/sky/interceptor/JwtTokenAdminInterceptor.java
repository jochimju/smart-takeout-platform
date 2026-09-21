package com.sky.interceptor;

import com.sky.constant.JwtClaimsConstant;
import com.sky.context.BaseContext;
import com.sky.mapper.PermissionMapper;
import com.sky.utils.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * jwt浠ょ墝鏍￠獙鐨勬嫤鎴櫒
 */
@Component
@Slf4j
public class JwtTokenAdminInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtProperties jwtProperties;
    @Autowired
    private PermissionMapper permissionMapper;

    /**
     * 鏍￠獙jwt
     *
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //鍒ゆ柇褰撳墠鎷︽埅鍒扮殑鏄疌ontroller鐨勬柟娉曡繕鏄叾浠栬祫婧?
        if (!(handler instanceof HandlerMethod)) {
            //褰撳墠鎷︽埅鍒扮殑涓嶆槸鍔ㄦ€佹柟娉曪紝鐩存帴鏀捐
            return true;
}
        //1銆佷粠璇锋眰澶翠腑鑾峰彇浠ょ墝
        String token = request.getHeader(jwtProperties.getAdminTokenName());

        //2銆佹牎楠屼护鐗?
        try {
            log.info("jwt鏍￠獙:{}", token);
            Claims claims = JwtUtil.parseJWT(jwtProperties.getAdminSecretKey(), token);
            Long empId = Long.valueOf(claims.get(JwtClaimsConstant.EMP_ID).toString());
            log.info("current employee id: {}", empId);
            BaseContext.setCurrentId(empId);
            if (!hasPermission(empId, request)) {
                response.setStatus(403);
                return false;
            }
            //3銆侀€氳繃锛屾斁琛?
            return true;
        } catch (Exception ex) {
            //4銆佷笉閫氳繃锛屽搷搴?01鐘舵€佺爜
            response.setStatus(401);
            return false;
        }
    }

    private boolean hasPermission(Long empId, HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        int configured = permissionMapper.countPermission(method, path);
        if (configured == 0) {
            return true;
        }
        return permissionMapper.countEmployeePermission(empId, method, path) > 0;
    }
}
