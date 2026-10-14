package com.sky.websocket;

import com.sky.contract.account.SessionCheck;
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class NotificationAccountAccess {
    private final DiscoveryClient discovery;
    private final String baseUrl;
    private final RestClient client;
    public NotificationAccountAccess(DiscoveryClient discovery,
            @Value("${sky.security.account-url:}") String baseUrl, @Value("${sky.internal.token}") String internal) {
        this.discovery = discovery; this.baseUrl = baseUrl;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = RestClient.builder().requestFactory(factory).defaultHeader("X-Internal-Token", internal).build();
    }
    private String base() {
        if (!baseUrl.isBlank()) return baseUrl;
        var instances = discovery.getInstances("account-service");
        if (instances.isEmpty()) throw new IllegalStateException("account-service unavailable");
        return instances.get(0).getUri().toString();
    }
    public boolean allowed(String token, long employeeId, String path) {
        try {
            String base = base();
            if (!Boolean.TRUE.equals(client.post().uri(base + "/internal/account/sessions/check")
                    .body(new SessionCheck(token, true)).retrieve().body(Boolean.class))) return false;
            return Boolean.TRUE.equals(client.get().uri(base + "/internal/account/permissions/check?employeeId={id}&method=GET&path={path}", employeeId, path)
                    .retrieve().body(Boolean.class));
        } catch (RuntimeException ex) { return false; }
    }
}
