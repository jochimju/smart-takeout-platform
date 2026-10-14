ALTER TABLE seckill_activity ADD COLUMN runtime_retired TINYINT NOT NULL DEFAULT 0,
 ADD INDEX idx_activity_runtime(runtime_retired,id);
ALTER TABLE seckill_cache_event ADD COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
CREATE INDEX idx_mq_archive ON mq_fail_message(status,update_time,id);
CREATE TABLE trade_runtime_archive (
 source VARCHAR(32) NOT NULL, event_key VARCHAR(64) NOT NULL, payload MEDIUMTEXT NOT NULL,
 archived_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY(source,event_key)
) ENGINE=InnoDB;
CREATE TABLE trade_replay_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, source VARCHAR(32) NOT NULL, event_key VARCHAR(64) NOT NULL,
 operator_id BIGINT NOT NULL, reason VARCHAR(500) NOT NULL, create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
