CREATE INDEX idx_mq_expired_lease ON mq_fail_message(status,lease_until,id);
