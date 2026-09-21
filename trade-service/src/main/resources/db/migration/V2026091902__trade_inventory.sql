CREATE TABLE IF NOT EXISTS trade_inventory (
  product_type VARCHAR(16) NOT NULL,
  product_id BIGINT NOT NULL,
  stock INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  catalog_version BIGINT NOT NULL DEFAULT 0,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (product_type, product_id),
  CONSTRAINT chk_trade_inventory_stock CHECK (stock >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
