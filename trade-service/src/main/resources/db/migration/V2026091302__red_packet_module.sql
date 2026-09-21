CREATE TABLE red_packet_package (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    sale_price DECIMAL(12,2) NOT NULL,
    packet_count INT NOT NULL,
    packet_amount DECIMAL(12,2) NOT NULL,
    valid_months INT NOT NULL DEFAULT 1,
    status INT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CHECK (sale_price >= 0 AND packet_count > 0 AND packet_amount > 0 AND valid_months > 0)
) ENGINE=InnoDB COMMENT='red packet package';

INSERT INTO red_packet_package(name, sale_price, packet_count, packet_amount, valid_months, status)
SELECT '10元红包包', 10.00, 5, 5.00, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM red_packet_package WHERE name='10元红包包');

CREATE TABLE red_packet_purchase_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    package_id BIGINT NOT NULL,
    pay_amount DECIMAL(12,2) NOT NULL,
    packet_count_snapshot INT NOT NULL,
    packet_amount_snapshot DECIMAL(12,2) NOT NULL,
    valid_months_snapshot INT NOT NULL,
    status INT NOT NULL DEFAULT 0 COMMENT '0 pending, 1 paid, 2 closed',
    transaction_id VARCHAR(128) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_time DATETIME NULL,
    UNIQUE KEY uk_red_packet_purchase_order_no(order_no),
    UNIQUE KEY uk_red_packet_purchase_transaction(transaction_id),
    KEY idx_red_packet_purchase_user_time(user_id, create_time)
) ENGINE=InnoDB COMMENT='red packet purchase order';

CREATE TABLE user_red_packet (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    package_id BIGINT NOT NULL,
    purchase_order_id BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    status INT NOT NULL DEFAULT 0 COMMENT '0 unused, 1 reserved, 2 used, 3 expired',
    receive_time DATETIME NOT NULL,
    expire_time DATETIME NOT NULL,
    used_time DATETIME NULL,
    food_order_id BIGINT NULL,
    UNIQUE KEY uk_red_packet_purchase_sequence(purchase_order_id, sequence_no),
    UNIQUE KEY uk_red_packet_food_order(food_order_id),
    KEY idx_user_red_packet_available(user_id, status, expire_time, amount)
) ENGINE=InnoDB COMMENT='user red packet asset';

ALTER TABLE orders ADD COLUMN user_red_packet_id BIGINT NULL COMMENT 'used user red packet instance';
ALTER TABLE orders ADD KEY idx_orders_user_red_packet(user_red_packet_id);
