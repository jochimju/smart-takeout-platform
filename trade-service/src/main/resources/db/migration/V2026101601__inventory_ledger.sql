CREATE TABLE trade_inventory_adjustment (
 adjustment_id VARCHAR(64) PRIMARY KEY,
 product_type VARCHAR(16) NOT NULL,
 product_id BIGINT NOT NULL,
 delta INT NOT NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
CREATE TABLE seckill_inventory_allocation (
 activity_id BIGINT PRIMARY KEY,
 setmeal_id BIGINT NOT NULL,
 quantity INT NOT NULL,
 returned_quantity INT NOT NULL DEFAULT 0,
 state VARCHAR(16) NOT NULL DEFAULT 'ALLOCATED',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CHECK(quantity>=0), CHECK(returned_quantity>=0 AND returned_quantity<=quantity)
) ENGINE=InnoDB;
