-- CloudMall Pay LocalTCC receipt and official Seata 1.5.2 Fence migration.
USE cloudmall_pay;
CREATE TABLE IF NOT EXISTS pay_tcc_receipt (
  id BIGINT PRIMARY KEY,
  pay_no VARCHAR(64) NOT NULL,
  xid VARCHAR(128) NOT NULL,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  operation_key VARCHAR(128) NOT NULL,
  ledger_key VARCHAR(128) NOT NULL,
  branch_id BIGINT,
  state VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_pay_receipt_pay(pay_no),
  UNIQUE KEY uk_pay_receipt_xid(xid),
  UNIQUE KEY uk_pay_receipt_operation(operation_key),
  UNIQUE KEY uk_pay_receipt_ledger(ledger_key)
);
CREATE TABLE IF NOT EXISTS tcc_fence_log (
  xid VARCHAR(128) NOT NULL,
  branch_id BIGINT NOT NULL,
  action_name VARCHAR(64) NOT NULL,
  status TINYINT NOT NULL,
  gmt_create DATETIME(3) NOT NULL,
  gmt_modified DATETIME(3) NOT NULL,
  PRIMARY KEY (xid,branch_id),
  KEY idx_gmt_modified(gmt_modified),
  KEY idx_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
