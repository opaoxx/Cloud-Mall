-- CloudMall User LocalTCC official Seata 1.5.2 Fence table migration.
USE cloudmall_user;
CREATE TABLE IF NOT EXISTS tcc_fence_log (xid VARCHAR(128) NOT NULL,branch_id BIGINT NOT NULL,action_name VARCHAR(64) NOT NULL,status TINYINT NOT NULL,gmt_create DATETIME(3) NOT NULL,gmt_modified DATETIME(3) NOT NULL,PRIMARY KEY (xid,branch_id),KEY idx_gmt_modified(gmt_modified),KEY idx_status(status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
