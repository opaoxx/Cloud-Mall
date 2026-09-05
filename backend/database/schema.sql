-- CloudMall local development schema. Execute in MySQL 8.0 after creating logical databases.
SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS cloudmall_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS cloudmall_product DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS cloudmall_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS cloudmall_stock DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS cloudmall_pay DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE cloudmall_user;
CREATE TABLE IF NOT EXISTS mall_user (id BIGINT PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL, role VARCHAR(16) NOT NULL DEFAULT 'USER', balance DECIMAL(18,2) NOT NULL DEFAULT 10000.00, nickname VARCHAR(128), phone VARCHAR(32), avatar_url VARCHAR(512), status TINYINT NOT NULL DEFAULT 1, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL);
SET @balance_column_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'mall_user' AND column_name = 'balance');
SET @balance_migration := IF(@balance_column_exists = 0, 'ALTER TABLE mall_user ADD COLUMN balance DECIMAL(18,2) NOT NULL DEFAULT 10000.00 AFTER role', 'SELECT 1');
PREPARE balance_migration_statement FROM @balance_migration;
EXECUTE balance_migration_statement;
DEALLOCATE PREPARE balance_migration_statement;
CREATE TABLE IF NOT EXISTS user_address (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, receiver_name VARCHAR(64) NOT NULL, receiver_phone VARCHAR(32) NOT NULL, region_detail VARCHAR(512) NOT NULL, is_default TINYINT NOT NULL DEFAULT 0, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, KEY idx_user_id(user_id));
USE cloudmall_product;
CREATE TABLE IF NOT EXISTS product_category (id BIGINT PRIMARY KEY, parent_id BIGINT NOT NULL DEFAULT 0, name VARCHAR(128) NOT NULL, sort_no INT NOT NULL DEFAULT 0, status TINYINT NOT NULL DEFAULT 1, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL);
CREATE TABLE IF NOT EXISTS product (id BIGINT PRIMARY KEY, category_id BIGINT NOT NULL, name VARCHAR(255) NOT NULL, main_image VARCHAR(512), description TEXT, price DECIMAL(18,2) NOT NULL, status TINYINT NOT NULL DEFAULT 0, version INT NOT NULL DEFAULT 0, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, KEY idx_category_status(category_id,status));
CREATE TABLE IF NOT EXISTS product_sku (id BIGINT PRIMARY KEY, product_id BIGINT NOT NULL, sku_code VARCHAR(64) NOT NULL UNIQUE, spec_json JSON NOT NULL, price DECIMAL(18,2) NOT NULL, status TINYINT NOT NULL DEFAULT 1, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL);
CREATE TABLE IF NOT EXISTS product_parameter (id BIGINT PRIMARY KEY, product_id BIGINT NOT NULL, param_name VARCHAR(128) NOT NULL, param_value VARCHAR(512) NOT NULL, sort_no INT NOT NULL DEFAULT 0, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, KEY idx_product_sort(product_id,sort_no));
CREATE TABLE IF NOT EXISTS product_hot_stat (product_id BIGINT PRIMARY KEY, view_count BIGINT NOT NULL DEFAULT 0, search_count BIGINT NOT NULL DEFAULT 0, hot_score DECIMAL(18,6) NOT NULL DEFAULT 0, stat_date DATE NOT NULL, updated_at DATETIME(3) NOT NULL);
CREATE TABLE IF NOT EXISTS seckill_activity (id BIGINT PRIMARY KEY, sku_id BIGINT NOT NULL, start_at DATETIME(3) NOT NULL, end_at DATETIME(3) NOT NULL, stock_limit INT NOT NULL, per_user_limit INT NOT NULL, status VARCHAR(16) NOT NULL, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, KEY idx_activity_window(status,start_at,end_at));
USE cloudmall_stock;
CREATE TABLE IF NOT EXISTS stock_sku (sku_id BIGINT PRIMARY KEY, product_id BIGINT NOT NULL, total_quantity INT NOT NULL, available_quantity INT NOT NULL, reserved_quantity INT NOT NULL, sold_quantity INT NOT NULL, version INT NOT NULL DEFAULT 0, updated_at DATETIME(3) NOT NULL);
CREATE TABLE IF NOT EXISTS stock_flow (id BIGINT PRIMARY KEY, sku_id BIGINT NOT NULL, order_no VARCHAR(64) NOT NULL, flow_type VARCHAR(32) NOT NULL, quantity INT NOT NULL, idempotency_key VARCHAR(128) NOT NULL UNIQUE, created_at DATETIME(3) NOT NULL);
CREATE TABLE IF NOT EXISTS seckill_reservation (id BIGINT PRIMARY KEY, activity_id BIGINT NOT NULL, sku_id BIGINT NOT NULL, user_id BIGINT NOT NULL, order_no VARCHAR(64) NOT NULL, idempotency_key VARCHAR(128) NOT NULL, status VARCHAR(16) NOT NULL, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, UNIQUE KEY uk_seckill_request(activity_id,user_id,idempotency_key), UNIQUE KEY uk_seckill_order(order_no), KEY idx_seckill_stock(activity_id,sku_id,status));
USE cloudmall_pay;
CREATE TABLE IF NOT EXISTS pay_record (id BIGINT PRIMARY KEY, pay_no VARCHAR(64) NOT NULL UNIQUE, order_no VARCHAR(64) NOT NULL UNIQUE, user_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL, status VARCHAR(32) NOT NULL, paid_at DATETIME(3), created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL);
CREATE TABLE IF NOT EXISTS pay_callback_log (id BIGINT PRIMARY KEY, pay_no VARCHAR(64) NOT NULL, callback_id VARCHAR(128) NOT NULL UNIQUE, callback_status VARCHAR(32) NOT NULL, payload JSON, processed_at DATETIME(3), created_at DATETIME(3) NOT NULL);
USE cloudmall_order;
-- Pre-create the current and near-term physical tables required by the frozen monthly strategy.
CREATE TABLE IF NOT EXISTS mall_order_template (id BIGINT PRIMARY KEY, order_no VARCHAR(64) NOT NULL UNIQUE, user_id BIGINT NOT NULL, status VARCHAR(32) NOT NULL, total_amount DECIMAL(18,2) NOT NULL, pay_amount DECIMAL(18,2) NOT NULL, address_snapshot JSON NOT NULL, expire_at DATETIME(3), paid_at DATETIME(3), cancelled_at DATETIME(3), created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, KEY idx_user_created(user_id,created_at), KEY idx_status_expire(status,expire_at));
CREATE TABLE IF NOT EXISTS mall_order_202608 LIKE mall_order_template;
CREATE TABLE IF NOT EXISTS mall_order_item_202608 (id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, order_no VARCHAR(64) NOT NULL, product_id BIGINT NOT NULL, sku_id BIGINT NOT NULL, product_name_snapshot VARCHAR(255) NOT NULL, sku_snapshot JSON, unit_price DECIMAL(18,2) NOT NULL, quantity INT NOT NULL, line_amount DECIMAL(18,2) NOT NULL, created_at DATETIME(3) NOT NULL, KEY idx_order_no(order_no));
CREATE TABLE IF NOT EXISTS order_status_log_202608 (id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, order_no VARCHAR(64) NOT NULL, from_status VARCHAR(32), to_status VARCHAR(32) NOT NULL, event_type VARCHAR(64) NOT NULL, operator_id BIGINT, remark VARCHAR(512), created_at DATETIME(3) NOT NULL, KEY idx_order_created(order_id,created_at));
CREATE TABLE IF NOT EXISTS mall_order_item_template LIKE mall_order_item_202608;
CREATE TABLE IF NOT EXISTS order_status_log_template LIKE order_status_log_202608;
CREATE TABLE IF NOT EXISTS order_idempotency (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, idempotency_key VARCHAR(128) NOT NULL, order_no VARCHAR(64) NOT NULL, created_at DATETIME(3) NOT NULL, UNIQUE KEY uk_order_idempotency(user_id,idempotency_key));

-- Small, repeatable local catalog. The source data was selected from the product
-- examples in F:\hmall\sql and adapted to CloudMall's product/SKU model.
USE cloudmall_product;
INSERT IGNORE INTO product_category(id,parent_id,name,sort_no,status,created_at,updated_at) VALUES
 (1001,0,'旅行箱包',10,1,NOW(3),NOW(3)),
 (1002,0,'食品饮料',20,1,NOW(3),NOW(3)),
 (1003,0,'家居生活',30,1,NOW(3),NOW(3)),
 (1004,0,'数码配件',40,1,NOW(3),NOW(3));
INSERT IGNORE INTO product(id,category_id,name,main_image,description,price,status,version,created_at,updated_at) VALUES
 (1001001,1001,'RIMOWA 21寸托运箱 SALSA AIR 果绿色','https://m.360buyimg.com/mobilecms/s720x720_jfs/t6934/364/1195375010/84676/e9f2c55f/597ece38N0ddcbc77.jpg!q70.jpg.webp','轻量耐用的旅行箱，适合短途出行。','289.00',1,0,NOW(3),NOW(3)),
 (1001002,1001,'美旅 20寸万向轮旅行箱','https://m.360buyimg.com/mobilecms/s720x720_jfs/t1/22734/21/2036/130399/5c18af2aEab296c01/7b148f18c6081654.jpg!q70.jpg.webp','商务出行与登机旅行的轻便选择。','299.00',1,0,NOW(3),NOW(3)),
 (1001003,1001,'莎米特 28寸 PC 大容量行李箱','https://m.360buyimg.com/mobilecms/s720x720_jfs/t30454/163/719393962/79149/13bcc06a/5bfca9b6N493202d2.jpg!q70.jpg.webp','PC 材质箱体，容量充足，适合长途旅行。','713.00',1,0,NOW(3),NOW(3)),
 (1002001,1002,'德亚全脂纯牛奶 1L*6盒','https://m.360buyimg.com/mobilecms/s720x720_jfs/t25771/98/1071132390/186396/29fcfa36/5b879ac4Nca072d7c.jpg!q70.jpg.webp','进口全脂牛奶礼盒装。','85.90',1,0,NOW(3),NOW(3)),
 (1002002,1002,'欧德堡全脂纯牛奶 1L*12盒','https://m.360buyimg.com/mobilecms/s720x720_jfs/t24328/97/2181458616/279103/83a4a2a7/5b753954N5657e230.jpg!q70.jpg.webp','日常早餐囤货装。','42.90',1,0,NOW(3),NOW(3)),
 (1003001,1003,'北欧简约陶瓷马克杯',NULL,'简约耐看的日常饮水杯。','39.90',1,0,NOW(3),NOW(3)),
 (1003002,1003,'便携折叠收纳箱',NULL,'宿舍和居家都适用的收纳好物。','29.90',1,0,NOW(3),NOW(3)),
 (1004001,1004,'无线蓝牙耳机 Pro',NULL,'通勤与运动场景均适用。','199.00',1,0,NOW(3),NOW(3));
INSERT IGNORE INTO product_sku(id,product_id,sku_code,spec_json,price,status,created_at,updated_at) VALUES
 (1101001,1001001,'RIMOWA-21-GREEN','{"颜色":"果绿色","尺寸":"21寸"}','289.00',1,NOW(3),NOW(3)),
 (1101002,1001002,'AMT-20-BLACK','{"颜色":"黑色","尺寸":"20寸"}','299.00',1,NOW(3),NOW(3)),
 (1101003,1001003,'SUMMIT-28-BLACK','{"颜色":"黑色","尺寸":"28寸"}','713.00',1,NOW(3),NOW(3)),
 (1102001,1002001,'WEI-1L-6','{"规格":"1L*6盒"}','85.90',1,NOW(3),NOW(3)),
 (1102002,1002002,'OLD-1L-12','{"规格":"1L*12盒"}','42.90',1,NOW(3),NOW(3)),
 (1103001,1003001,'MUG-WHITE','{"颜色":"米白色"}','39.90',1,NOW(3),NOW(3)),
 (1103002,1003002,'BOX-LARGE','{"尺寸":"大号"}','29.90',1,NOW(3),NOW(3)),
 (1104001,1004001,'EARPHONE-BLACK','{"颜色":"曜石黑"}','199.00',1,NOW(3),NOW(3));
INSERT IGNORE INTO product_parameter(id,product_id,param_name,param_value,sort_no,created_at,updated_at) VALUES
 (1201001,1001001,'材质','PC+铝框',1,NOW(3),NOW(3)),
 (1201002,1001002,'材质','耐磨 ABS',1,NOW(3),NOW(3)),
 (1202001,1002001,'产地','德国',1,NOW(3),NOW(3)),
 (1204001,1004001,'连接方式','蓝牙 5.3',1,NOW(3),NOW(3));
USE cloudmall_stock;
INSERT IGNORE INTO stock_sku(sku_id,product_id,total_quantity,available_quantity,reserved_quantity,sold_quantity,version,updated_at) VALUES
 (1101001,1001001,10000,10000,0,0,0,NOW(3)),
 (1101002,1001002,10000,10000,0,0,0,NOW(3)),
 (1101003,1001003,10000,10000,0,0,0,NOW(3)),
 (1102001,1002001,10000,10000,0,0,0,NOW(3)),
 (1102002,1002002,10000,10000,0,0,0,NOW(3)),
 (1103001,1003001,10000,10000,0,0,0,NOW(3)),
 (1103002,1003002,10000,10000,0,0,0,NOW(3)),
 (1104001,1004001,10000,10000,0,0,0,NOW(3));
