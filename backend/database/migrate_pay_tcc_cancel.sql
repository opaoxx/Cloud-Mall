-- CloudMall Pay TCC active cancellation ledger migration.
USE cloudmall_pay;
CREATE TABLE IF NOT EXISTS pay_tcc_cancel (
  id BIGINT PRIMARY KEY,
  pay_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  state VARCHAR(32) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3) NOT NULL,
  lease_owner VARCHAR(128),
  lease_until DATETIME(3),
  last_error VARCHAR(512),
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_pay_tcc_cancel_pay(pay_no),
  KEY idx_pay_tcc_cancel_due(state,next_retry_at,lease_until)
);
