package com.sky.config;
import com.alibaba.druid.pool.DruidDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;
@Configuration(proxyBeanMethods=false)
public class DatabasePoolMetricsConfiguration {
    @Bean public MeterBinder databasePoolMetrics(DataSource source) {
        return registry->{
            if(source instanceof DruidDataSource pool) {
                Gauge.builder("sky.jdbc.pool.max",pool,DruidDataSource::getMaxActive).register(registry);
                Gauge.builder("sky.jdbc.pool.active",pool,DruidDataSource::getActiveCount).register(registry);
                Gauge.builder("sky.jdbc.pool.idle",pool,DruidDataSource::getPoolingCount).register(registry);
                Gauge.builder("sky.jdbc.pool.pending",pool,DruidDataSource::getWaitThreadCount).register(registry);
            }
        };
    }
}
