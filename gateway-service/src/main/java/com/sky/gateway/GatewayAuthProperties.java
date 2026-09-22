package com.sky.gateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sky.gateway.auth")
public class GatewayAuthProperties {
    /** Browser WebSocket APIs cannot set arbitrary headers. Keep disabled unless required. */
    private boolean allowWebsocketQueryToken = false;
}
