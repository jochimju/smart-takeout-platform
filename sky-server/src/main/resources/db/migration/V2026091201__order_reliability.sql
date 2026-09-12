ALTER TABLE orders ADD COLUMN expire_time DATETIME NULL;
UPDATE orders SET expire_time=DATE_ADD(order_time, INTERVAL 15 MINUTE) WHERE expire_time IS NULL;
CREATE TABLE order_reliability_job (
 id VARCHAR(96) PRIMARY KEY, order_number VARCHAR(64) NOT NULL, kind VARCHAR(16) NOT NULL,
 due_at DATETIME NOT NULL, next_at DATETIME NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'READY',
 attempts INT NOT NULL DEFAULT 0, lease_token VARCHAR(64), lease_until DATETIME, last_error VARCHAR(1000),
 amount DECIMAL(12,2), mock_payment BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_order_job(order_number,kind), KEY idx_job_dispatch(state,next_at), KEY idx_job_lease(state,lease_until)
) ENGINE=InnoDB;
CREATE TABLE order_payment_receipt (
 order_number VARCHAR(64) PRIMARY KEY, transaction_id VARCHAR(128) NOT NULL,
 amount DECIMAL(12,2) NOT NULL, mock_payment BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_payment_transaction(transaction_id)
) ENGINE=InnoDB;
-- One-time backfill of historical pending orders; runtime only scans the event table.
INSERT INTO order_reliability_job(id,order_number,kind,due_at,next_at)
 SELECT CONCAT('timeout:',number),number,'TIMEOUT',expire_time,NOW()
 FROM orders WHERE status=1 AND pay_status=0 AND expire_time IS NOT NULL;
