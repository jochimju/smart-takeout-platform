package com.sky.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration.HttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Uses the HC5 adapter and pool created by OpenFeign auto-configuration. */
@Configuration(proxyBeanMethods=false)
public class FeignPoolConfiguration {
    @Bean public HttpClientBuilderCustomizer boundedFeignTransport() {
        // HTTP retries would amplify overload; business retries retain their explicit idempotency rules.
        return builder->builder.disableAutomaticRetries().evictIdleConnections(TimeValue.ofSeconds(30));
    }
    @Bean public SmartInitializingSingleton feignPoolMetrics(ObjectProvider<HttpClientConnectionManager> managers,
            ObjectProvider<MeterRegistry> registries) {
        return ()->{
            var manager=managers.getIfAvailable();var registry=registries.getIfAvailable();
            if(manager instanceof PoolingHttpClientConnectionManager pool && registry!=null) {
                Gauge.builder("feign.pool.leased",pool,p->p.getTotalStats().getLeased()).register(registry);
                Gauge.builder("feign.pool.pending",pool,p->p.getTotalStats().getPending()).register(registry);
                Gauge.builder("feign.pool.available",pool,p->p.getTotalStats().getAvailable()).register(registry);
                Gauge.builder("feign.pool.max",pool,p->p.getTotalStats().getMax()).register(registry);
            }
        };
    }
}
