-- CloudMall Pay TCC dispatch migration; safe to execute repeatedly.
USE cloudmall_pay;
CREATE TABLE IF NOT EXISTS pay_tcc_dispatch (
  id BIGINT PRIMARY KEY,
  pay_no VARCHAR(64) NOT NULL,
  xid VARCHAR(128) NOT NULL,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  operation_key VARCHAR(128) NOT NULL,
  ledger_key VARCHAR(128) NOT NULL,
  state VARCHAR(32) NOT NULL,
  post_attempted_at DATETIME(3),
  user_branch_id BIGINT,
  last_error VARCHAR(512),
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_pay_tcc_dispatch_pay(pay_no),
  UNIQUE KEY uk_pay_tcc_dispatch_xid(xid),
  UNIQUE KEY uk_pay_tcc_dispatch_operation(operation_key),
  UNIQUE KEY uk_pay_tcc_dispatch_ledger(ledger_key)
);
