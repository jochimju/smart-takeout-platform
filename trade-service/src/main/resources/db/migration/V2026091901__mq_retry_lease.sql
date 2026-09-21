ALTER TABLE mq_fail_message ADD COLUMN lease_until DATETIME NULL;
CREATE INDEX idx_mq_fail_retry_lease ON mq_fail_message(status, lease_until);
