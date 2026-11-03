-- CM21 opt-in schema; execute only on the explicitly selected connection.
-- No legacy rows or counters are migrated or reset.
SET time_zone = '+08:00';

CREATE TABLE IF NOT EXISTS product_metric_control (
  singleton_id TINYINT NOT NULL,
  series_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  started_at DATETIME(3) NOT NULL,
  index_uuid VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  index_ready TINYINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(singleton_id),
  CONSTRAINT ck_metric_control CHECK(singleton_id=1 AND revision>=0 AND index_ready IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS product_online_stat (
  product_id BIGINT NOT NULL,
  view_count BIGINT NOT NULL DEFAULT 0,
  search_count BIGINT NOT NULL DEFAULT 0,
  sold_count BIGINT NOT NULL DEFAULT 0,
  hot_score BIGINT NOT NULL DEFAULT 0,
  metric_version BIGINT NOT NULL DEFAULT 0,
  last_event_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY(product_id),
  CONSTRAINT ck_online_stat CHECK(product_id>0 AND view_count>=0 AND search_count>=0 AND sold_count>=0 AND hot_score>=0 AND metric_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS product_metric_applied_event (
  event_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  product_id BIGINT NOT NULL,
  sku_id BIGINT NULL,
  quantity INT NOT NULL,
  order_no VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  source_ref VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  source_revision BIGINT NULL,
  occurred_at DATETIME(3) NOT NULL,
  applied_at DATETIME(3) NOT NULL,
  metric_version BIGINT NOT NULL,
  PRIMARY KEY(event_id),
  UNIQUE KEY uk_metric_applied_version(product_id,metric_version),
  CONSTRAINT ck_metric_applied CHECK(product_id>0 AND quantity>0 AND metric_version>0 AND (sku_id IS NULL OR sku_id>0) AND (source_revision IS NULL OR source_revision>=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS product_metric_outbox (
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
  PRIMARY KEY(event_id),
  KEY idx_metric_outbox_due(status,next_retry_at,lease_until,event_id),
  CONSTRAINT ck_metric_outbox CHECK(product_id>0 AND attempts>=0 AND status IN ('PENDING','UNKNOWN','PUBLISHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS product_metric_projection_outbox (
  product_id BIGINT NOT NULL,
  metric_version BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NOT NULL,
  lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_until DATETIME(3) NULL,
  last_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  PRIMARY KEY(product_id,metric_version),
  KEY idx_metric_projection_due(status,next_retry_at,lease_until,product_id),
  CONSTRAINT ck_metric_projection CHECK(product_id>0 AND metric_version>0 AND attempts>=0 AND status IN ('PENDING','UNKNOWN','DONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS product_metric_quarantine (
  quarantine_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  queue_name VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_size BIGINT NOT NULL,
  raw_prefix MEDIUMBLOB NOT NULL,
  raw_complete TINYINT NOT NULL,
  event_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN',
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY(quarantine_id),
  UNIQUE KEY uk_metric_quarantine_payload(queue_name,payload_sha256),
  KEY idx_metric_quarantine_open(status,created_at),
  CONSTRAINT ck_metric_quarantine CHECK(payload_size>=0 AND raw_complete IN (0,1) AND status IN ('OPEN','RESOLVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO product_metric_control(singleton_id,series_id,revision,started_at,index_ready,updated_at)
SELECT 1,'cm21-online-v1',0,NOW(3),0,NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM product_metric_control WHERE singleton_id=1);
