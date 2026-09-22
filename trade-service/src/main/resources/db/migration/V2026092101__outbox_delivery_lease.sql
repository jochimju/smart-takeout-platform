ALTER TABLE business_event_outbox
  ADD COLUMN delivery_lease_until DATETIME NULL AFTER next_retry_time,
  ADD COLUMN delivery_token VARCHAR(64) NULL AFTER delivery_lease_until,
  ADD COLUMN last_error VARCHAR(512) NULL AFTER retry_count,
  ADD KEY idx_outbox_delivery_lease (status, next_retry_time, delivery_lease_until);
