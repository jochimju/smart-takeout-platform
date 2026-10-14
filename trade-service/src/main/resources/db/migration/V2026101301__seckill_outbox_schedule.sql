ALTER TABLE mq_fail_message ADD COLUMN available_at DATETIME(3) NULL;
CREATE INDEX idx_mq_route_ready ON mq_fail_message(routing_key,status,available_at,create_time);
