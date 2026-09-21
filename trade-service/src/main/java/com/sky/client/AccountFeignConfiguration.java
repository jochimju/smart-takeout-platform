package com.sky.client;

import feign.RequestInterceptor;
import feign.Retryer;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class AccountFeignConfiguration {
    @Bean
    public RequestInterceptor accountInternalToken(@Value("${sky.internal.token}") String token) {
        return template -> {
            template.header("X-Internal-Token", token);
            String traceId = MDC.get("traceId");
            if (traceId != null) template.header("X-Trace-Id", traceId);
        };
    }

    @Bean
    Retryer accountQueryRetryer() {
        return new Retryer.Default(100, 1_000, 2);
    }
}
