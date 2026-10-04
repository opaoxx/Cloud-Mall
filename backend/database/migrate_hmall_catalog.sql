-- CloudMall 商品目录 ETL。
-- 前置条件：已将 F:\hmall\sql\hm-item.sql 导入当前 MySQL，源表为 `hm-item`.item。
-- 本脚本只重建商品域和库存域的演示目录，不触碰用户、地址、订单和支付事实。
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
USE cloudmall_product;

START TRANSACTION;

DROP TEMPORARY TABLE IF EXISTS hmall_category_map;
CREATE TEMPORARY TABLE hmall_category_map (
  category_name VARCHAR(128) NOT NULL PRIMARY KEY,
  category_id BIGINT NOT NULL UNIQUE
) ENGINE = InnoDB;

INSERT INTO hmall_category_map (category_name, category_id)
SELECT category_name, 900000000000 + CRC32(CONVERT(category_name USING utf8mb4))
FROM (
  SELECT DISTINCT LEFT(COALESCE(NULLIF(TRIM(category), ''), '未分类'), 128) AS category_name
  FROM `hm-item`.item
) AS categories;

-- category_id 有唯一约束；CRC32 碰撞会在这里直接报错并回滚事务，避免静默合并。

-- 商品与库存是可重建的演示目录；历史订单、用户和支付数据保留。
DELETE FROM cloudmall_stock.stock_flow;
DELETE FROM cloudmall_stock.seckill_reservation;
DELETE FROM cloudmall_stock.stock_sku;
DELETE FROM cloudmall_product.seckill_activity;
DELETE FROM cloudmall_product.product_hot_stat;
DELETE FROM cloudmall_product.product_parameter;
DELETE FROM cloudmall_product.product_sku;
DELETE FROM cloudmall_product.product;
DELETE FROM cloudmall_product.product_category;

INSERT INTO cloudmall_product.product_category
    (id, parent_id, name, sort_no, status, created_at, updated_at)
SELECT
  category_id,
  0,
  category_name,
  ROW_NUMBER() OVER (ORDER BY category_name),
  1,
  NOW(3),
  NOW(3)
FROM hmall_category_map;

INSERT INTO cloudmall_product.product
    (id, category_id, name, main_image, description, price, status, version, created_at, updated_at)
SELECT
  item.id,
  category_map.category_id,
  item.name,
  NULLIF(item.image, ''),
  CONCAT_WS(
    ' · ',
    NULLIF(CONCAT('品牌：', NULLIF(item.brand, '')), '品牌：'),
    NULLIF(CONCAT('规格：', NULLIF(item.spec, '')), '规格：'),
    CONCAT('销量：', COALESCE(item.sold, 0)),
    CONCAT('评价：', COALESCE(item.comment_count, 0)),
    IF(item.isAD = 1, '推广商品', NULL)
  ),
  CAST(item.price AS DECIMAL(18, 2)) / 100,
  IF(item.status = 1, 1, 0),
  0,
  item.create_time,
  item.update_time
FROM `hm-item`.item AS item
JOIN hmall_category_map AS category_map
  ON category_map.category_name = LEFT(COALESCE(NULLIF(TRIM(item.category), ''), '未分类'), 128);

-- 每个源 item 作为一个独立 SKU，使用独立命名空间避免与商品主键混淆。
INSERT INTO cloudmall_product.product_sku
    (id, product_id, sku_code, spec_json, price, status, created_at, updated_at)
SELECT
  700000000000 + item.id,
  item.id,
  CONCAT('HM-', item.id),
  CASE
    WHEN JSON_TYPE(CAST(item.spec AS JSON)) = 'OBJECT' THEN CAST(item.spec AS JSON)
    ELSE JSON_OBJECT()
  END,
  CAST(item.price AS DECIMAL(18, 2)) / 100,
  IF(item.status = 1, 1, 0),
  item.create_time,
  item.update_time
FROM `hm-item`.item AS item;

INSERT INTO cloudmall_product.product_parameter
    (id, product_id, param_name, param_value, sort_no, created_at, updated_at)
SELECT
  600000000000 + item.id * 2,
  item.id,
  '品牌',
  LEFT(item.brand, 512),
  0,
  item.create_time,
  item.update_time
FROM `hm-item`.item AS item
WHERE item.brand IS NOT NULL AND TRIM(item.brand) <> ''
UNION ALL
SELECT
  600000000000 + item.id * 2 + 1,
  item.id,
  '规格',
  LEFT(item.spec, 512),
  1,
  item.create_time,
  item.update_time
FROM `hm-item`.item AS item
WHERE item.spec IS NOT NULL AND TRIM(item.spec) <> '';

INSERT INTO cloudmall_product.product_hot_stat
    (product_id, view_count, search_count, hot_score, stat_date, updated_at, source_type, source_ref)
SELECT
  item.id,
  COALESCE(item.sold, 0),
  COALESCE(item.comment_count, 0),
  COALESCE(item.sold, 0),
  CURRENT_DATE,
  NOW(3),
  'LEGACY_IMPORT',
  'hm-item.item'
FROM `hm-item`.item AS item;

INSERT INTO cloudmall_stock.stock_sku
    (sku_id, product_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version, updated_at)
SELECT
  700000000000 + item.id,
  item.id,
  item.stock,
  item.stock,
  0,
  0,
  0,
  NOW(3)
FROM `hm-item`.item AS item;

-- 为首页提供一个真实可下单的限时秒杀活动；活动价沿用该 SKU 的真实销售价，避免前端虚构金额。
SET @featured_item_id := (
  SELECT id
  FROM `hm-item`.item
  WHERE status = 1 AND price >= 1000
  ORDER BY price ASC, id ASC
  LIMIT 1
);
SET @featured_item_id := COALESCE(
  @featured_item_id,
  (SELECT id FROM `hm-item`.item WHERE status = 1 AND price > 0 ORDER BY price ASC, id ASC LIMIT 1)
);
INSERT INTO cloudmall_product.seckill_activity
    (id, sku_id, start_at, end_at, stock_limit, per_user_limit, status, created_at, updated_at)
SELECT
  910000000001,
  700000000000 + @featured_item_id,
  NOW(3),
  DATE_ADD(NOW(3), INTERVAL 7 DAY),
  LEAST(item.stock, 500),
  1,
  'PUBLISHED',
  NOW(3),
  NOW(3)
FROM `hm-item`.item AS item
WHERE item.id = @featured_item_id;

-- 迁移验收：源 item、商品、SKU、库存必须一一对应。
SET @source_item_count := (SELECT COUNT(*) FROM `hm-item`.item);
SET @product_count := (SELECT COUNT(*) FROM cloudmall_product.product);
SET @sku_count := (SELECT COUNT(*) FROM cloudmall_product.product_sku);
SET @stock_count := (SELECT COUNT(*) FROM cloudmall_stock.stock_sku);

COMMIT;
SET FOREIGN_KEY_CHECKS = 1;

SELECT 'source_item_count' AS metric, @source_item_count AS value
UNION ALL SELECT 'product_count', @product_count
UNION ALL SELECT 'sku_count', @sku_count
UNION ALL SELECT 'stock_count', @stock_count
UNION ALL SELECT 'category_count', (SELECT COUNT(*) FROM cloudmall_product.product_category);
