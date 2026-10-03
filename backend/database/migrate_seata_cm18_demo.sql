-- Repeatable Seata 1.5.2 AT setup for existing CloudMall MySQL volumes.
-- Demo tables are isolated from real order, stock, payment, and balance facts.
USE cloudmall_order;
CREATE TABLE IF NOT EXISTS undo_log (
  branch_id BIGINT NOT NULL,
  xid VARCHAR(128) NOT NULL,
  context VARCHAR(128) NOT NULL,
  rollback_info LONGBLOB NOT NULL,
  log_status INT(11) NOT NULL,
  log_created DATETIME(6) NOT NULL,
  log_modified DATETIME(6) NOT NULL,
  UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS seata_cm18_order_demo (
  tx_id VARCHAR(64) PRIMARY KEY,
  tx_xid VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

USE cloudmall_stock;
CREATE TABLE IF NOT EXISTS undo_log (
  branch_id BIGINT NOT NULL,
  xid VARCHAR(128) NOT NULL,
  context VARCHAR(128) NOT NULL,
  rollback_info LONGBLOB NOT NULL,
  log_status INT(11) NOT NULL,
  log_created DATETIME(6) NOT NULL,
  log_modified DATETIME(6) NOT NULL,
  UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS seata_cm18_stock_demo (
  tx_id VARCHAR(64) PRIMARY KEY,
  tx_xid VARCHAR(128) NOT NULL,
  marker VARCHAR(32) NOT NULL,
  created_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
