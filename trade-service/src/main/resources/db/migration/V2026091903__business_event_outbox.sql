CREATE TABLE IF NOT EXISTS business_event_outbox (
  event_id VARCHAR(64) PRIMARY KEY,
  event_type VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(32) NOT NULL,
  aggregate_id VARCHAR(64) NOT NULL,
  payload TEXT NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sent_time DATETIME NULL,
  KEY idx_outbox_pending(status,next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
