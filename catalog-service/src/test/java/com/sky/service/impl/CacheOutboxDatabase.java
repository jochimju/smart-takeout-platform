package com.sky.service.impl;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class CacheOutboxDatabase {
    static JdbcTemplate create() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:cache_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__menu_cache_invalidation_outbox.sql")).execute(source);
        return new JdbcTemplate(source);
    }
}
