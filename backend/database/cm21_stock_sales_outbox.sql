-- CM21 opt-in schema; execute only on the explicitly selected connection.
-- No legacy rows or counters are migrated or reset.
SET time_zone = '+08:00';

CREATE TABLE IF NOT EXISTS stock_sales_outbox (
  event_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  product_id BIGINT NOT NULL,
  event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NOT NULL,
  lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_until DATETIME(3) NULL,
  published_at DATETIME(3) NULL,
  last_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  sku_id BIGINT NOT NULL,
  order_no VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  quantity INT NOT NULL,
  source_ref VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  source_revision BIGINT NOT NULL,
  PRIMARY KEY(event_id),
  UNIQUE KEY uk_stock_sales_identity(order_no,sku_id),
  KEY idx_stock_sales_due(status,next_retry_at,lease_until,event_id),
  CONSTRAINT ck_stock_sales CHECK(product_id>0 AND sku_id>0 AND quantity>0 AND source_revision>=0 AND attempts>=0 AND status IN ('PENDING','UNKNOWN','PUBLISHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

