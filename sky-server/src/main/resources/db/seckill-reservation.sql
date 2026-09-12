CREATE TABLE IF NOT EXISTS seckill_reservation (
  order_number VARCHAR(64) NOT NULL PRIMARY KEY,
  activity_id BIGINT NULL,
  setmeal_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  release_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 reserved, 1 release pending, 2 released',
  KEY idx_release_status (release_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 旧活动订单仅在套餐唯一对应一个活动时回填，避免关联到其他活动。
INSERT IGNORE INTO seckill_reservation(order_number, activity_id, setmeal_id, user_id, release_status)
SELECT g.order_number, a.activity_id, g.setmeal_id, g.user_id, IF(o.status=6,1,0)
FROM seckill_order_guard g JOIN orders o ON o.number=g.order_number
JOIN (SELECT setmeal_id, MIN(id) activity_id FROM seckill_activity GROUP BY setmeal_id HAVING COUNT(*)=1) a
ON a.setmeal_id=g.setmeal_id WHERE g.order_number LIKE 'SK%';

INSERT IGNORE INTO seckill_reservation(order_number, activity_id, setmeal_id, user_id, release_status)
SELECT g.order_number, NULL, g.setmeal_id, g.user_id, IF(o.status=6,1,0)
FROM seckill_order_guard g JOIN orders o ON o.number=g.order_number
WHERE g.order_number NOT LIKE 'SK%';
