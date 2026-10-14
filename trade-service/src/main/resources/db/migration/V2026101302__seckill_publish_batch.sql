ALTER TABLE mq_fail_message ADD COLUMN claim_token VARCHAR(36) NULL;
CREATE INDEX idx_mq_route_claim ON mq_fail_message(routing_key,status,id);
