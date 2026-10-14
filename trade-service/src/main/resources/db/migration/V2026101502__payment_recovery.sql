-- Appended after existing stock-bucket migration; safe for databases already on V2026101403.
CREATE TABLE payment_recovery_job (
 id VARCHAR(96) PRIMARY KEY, order_number VARCHAR(64) NOT NULL, kind VARCHAR(16) NOT NULL,
 amount DECIMAL(12,2) NOT NULL, deadline DATETIME NOT NULL, next_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 state VARCHAR(16) NOT NULL DEFAULT 'READY', attempts INT NOT NULL DEFAULT 0,
 lease_token VARCHAR(64), lease_until DATETIME, last_error VARCHAR(1000),
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_payment_recovery(order_number,kind), KEY idx_payment_recovery(state,next_at)
) ENGINE=InnoDB;
CREATE TABLE payment_reconciliation_run (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, bill_date DATE NOT NULL, file_sha256 CHAR(64) NOT NULL,
 source VARCHAR(24) NOT NULL,
 state VARCHAR(16) NOT NULL, row_count INT NOT NULL DEFAULT 0, difference_count INT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_reconciliation(bill_date,file_sha256)
) ENGINE=InnoDB;
CREATE TABLE payment_reconciliation_difference (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, run_id BIGINT NOT NULL, order_number VARCHAR(64),
 kind VARCHAR(32) NOT NULL, detail VARCHAR(500) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_reconciliation_run(run_id)
) ENGINE=InnoDB;
ALTER TABLE order_payment_receipt ADD COLUMN channel_paid_at DATETIME NULL;
ALTER TABLE order_payment_receipt ADD KEY idx_payment_channel_time(mock_payment,channel_paid_at);
