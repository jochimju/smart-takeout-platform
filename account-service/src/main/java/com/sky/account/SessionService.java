package com.sky.account;

import com.sky.utils.JwtUtil;
import com.sky.utils.properties.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SessionService {
    private final SessionMapper sessions;
    private final JwtProperties jwt;
    public Long version(Long id) { return sessions.version(id); }
    public void revoke(String token, boolean admin) {
        var claims = JwtUtil.parseJWT(admin ? jwt.getAdminSecretKey() : jwt.getUserSecretKey(), token);
        sessions.revoke(claims.getId(), claims.getExpiration());
    }
    @Scheduled(fixedDelay = 3600000) public void purge() { sessions.purge(); }
}
