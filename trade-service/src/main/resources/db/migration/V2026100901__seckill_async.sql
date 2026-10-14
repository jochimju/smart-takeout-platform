-- Stop old synchronous instances before applying and starting this version.
ALTER TABLE seckill_activity ADD COLUMN async_initialized TINYINT NOT NULL DEFAULT 0;

CREATE TABLE seckill_submit_request (
  order_number VARCHAR(64) NOT NULL PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  command_body MEDIUMTEXT NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
  accepted_at DATETIME(3) NOT NULL,
  deadline_at DATETIME(3) NOT NULL,
  next_at DATETIME(3) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_error VARCHAR(500) NULL,
  UNIQUE KEY uk_seckill_async_request (user_id, activity_id, request_id),
  KEY idx_seckill_async_ready (state, next_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE seckill_cache_event (
  order_number VARCHAR(64) NOT NULL PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  request_id VARCHAR(64) NULL,
  state VARCHAR(16) NOT NULL,
  last_error VARCHAR(500) NULL,
  next_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_seckill_cache_retry (next_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
