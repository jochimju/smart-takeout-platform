-- Apply once with the old application stopped. Reject occupied stock above total in preflight.
ALTER TABLE seckill_activity ADD COLUMN remaining_stock INT NULL,
  ADD COLUMN stock_version BIGINT NOT NULL DEFAULT 0;
UPDATE seckill_activity a LEFT JOIN
 (SELECT activity_id,COUNT(*) n FROM seckill_reservation WHERE activity_id IS NOT NULL AND release_status<>2 GROUP BY activity_id) r
 ON r.activity_id=a.id SET a.remaining_stock=a.stock-COALESCE(r.n,0);
ALTER TABLE seckill_activity MODIFY remaining_stock INT NOT NULL,
  ADD CONSTRAINT chk_seckill_remaining CHECK (remaining_stock>=0 AND remaining_stock<=stock);
ALTER TABLE seckill_reservation ADD COLUMN request_id VARCHAR(64) NULL,
  ADD COLUMN request_hash VARCHAR(64) NULL,
  ADD UNIQUE KEY uk_seckill_request(user_id,request_id);
