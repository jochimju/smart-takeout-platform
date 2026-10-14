package com.sky.websocket;

import com.sky.utils.JwtUtil;
import com.sky.utils.properties.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WebSocketSecurityTest {
    @Test void revokedAndUnprivilegedBrowserTokensNeverUpgrade() throws Exception {
        var jwt=new JwtProperties(); jwt.setAdminSecretKey("test-admin"); jwt.setAdminTokenName("token");
        var accounts=mock(NotificationAccountAccess.class); var filter=new WebSocketAuthenticationFilter(jwt,accounts,true);
        String token=JwtUtil.createJWT("test-admin",60000,Map.of("empId",7L,"av",0));
        var request=new MockHttpServletRequest("GET","/ws/browser"); request.setParameter("token",token);
        var response=new MockHttpServletResponse(); var chain=mock(jakarta.servlet.FilterChain.class);
        filter.doFilter(request,response,chain); assertEquals(403,response.getStatus()); verifyNoInteractions(chain);
        when(accounts.allowed(token,7L,"/ws/browser")).thenReturn(true);
        response=new MockHttpServletResponse(); request.removeAttribute(filter.getClass().getName()+".FILTERED");
        filter.doFilter(request,response,chain); verify(chain).doFilter(request,response);
    }
    @Test void missingOrWrongRealmTokenIsUnauthorized() throws Exception {
        var jwt=new JwtProperties(); jwt.setAdminSecretKey("test-admin"); jwt.setAdminTokenName("token");
        var filter=new WebSocketAuthenticationFilter(jwt,mock(NotificationAccountAccess.class),true);
        var request=new MockHttpServletRequest("GET","/ws/browser"); var response=new MockHttpServletResponse();
        filter.doFilter(request,response,mock(jakarta.servlet.FilterChain.class)); assertEquals(401,response.getStatus());
        request.setParameter("token",JwtUtil.createJWT("test-user",60000,Map.of("userId",9L)));
        response=new MockHttpServletResponse(); filter.doFilter(request,response,mock(jakarta.servlet.FilterChain.class)); assertEquals(401,response.getStatus());
    }
}
