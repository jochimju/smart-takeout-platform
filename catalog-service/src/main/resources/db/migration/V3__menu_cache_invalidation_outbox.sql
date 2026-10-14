CREATE TABLE menu_cache_invalidation (
    event_id VARCHAR(36) PRIMARY KEY,
    cache_kind VARCHAR(16) NOT NULL,
    status INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token VARCHAR(36),
    lease_until TIMESTAMP NULL,
    last_error VARCHAR(512),
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_time TIMESTAMP NULL,
    INDEX idx_menu_cache_pending (status, next_retry_time),
    INDEX idx_menu_cache_lease (status, lease_until)
);
