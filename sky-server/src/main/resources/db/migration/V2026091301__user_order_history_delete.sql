ALTER TABLE orders
  ADD COLUMN user_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '用户端是否隐藏：0否，1是',
  ADD INDEX idx_orders_user_deleted_time (user_id, user_deleted, order_time);
