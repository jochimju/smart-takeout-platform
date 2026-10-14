-- Existing initialized activities retain their original inventory ledger.
ALTER TABLE seckill_activity ADD COLUMN bucket_count INT NOT NULL DEFAULT 1;
CREATE TABLE seckill_stock_bucket (
 activity_id BIGINT NOT NULL, bucket_id INT NOT NULL,
 stock INT NOT NULL, remaining_stock INT NOT NULL, stock_version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(activity_id,bucket_id),
 CHECK(stock>=0 AND remaining_stock>=0 AND remaining_stock<=stock)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE seckill_submit_request ADD COLUMN bucket_id INT NOT NULL DEFAULT 0;
ALTER TABLE seckill_reservation ADD COLUMN bucket_id INT NOT NULL DEFAULT 0;
ALTER TABLE seckill_cache_event ADD COLUMN bucket_id INT NOT NULL DEFAULT 0;

-- Serializes repeated handoffs only; fulfillment never accesses this row.
CREATE TABLE seckill_handoff_lock (
 activity_id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
