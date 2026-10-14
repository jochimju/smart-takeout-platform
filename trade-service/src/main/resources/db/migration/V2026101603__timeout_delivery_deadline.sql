ALTER TABLE mq_fail_message ADD COLUMN delivery_deadline DATETIME(3) NULL;
UPDATE mq_fail_message SET delivery_deadline=available_at WHERE routing_key='seckill.timeout';
CREATE INDEX idx_mq_timeout_recovery ON mq_fail_message(routing_key,status,delivery_deadline);
