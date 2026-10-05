-- CM-24: independent final settlement fact for accepted seckill quotas.
USE cloudmall_stock;
CREATE TABLE IF NOT EXISTS seckill_quota_settlement (
  id BIGINT PRIMARY KEY,
  order_no VARCHAR(64) NOT NULL,
  activity_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  quantity INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  revision BIGINT NOT NULL,
  operation_key VARCHAR(160),
  redis_revision BIGINT,
  last_error VARCHAR(512),
  next_retry_at DATETIME(3),
  lease_owner VARCHAR(128),
  lease_until DATETIME(3),
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_seckill_settlement_order(order_no),
  UNIQUE KEY uk_seckill_settlement_operation(operation_key),
  KEY idx_seckill_settlement_retry(status,next_retry_at,lease_until),
  KEY idx_seckill_settlement_activity(activity_id,sku_id,status)
);
