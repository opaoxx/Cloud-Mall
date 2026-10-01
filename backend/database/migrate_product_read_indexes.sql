-- Existing MySQL volumes do not rerun schema.sql. Apply this migration once or rerun safely.
USE cloudmall_product;

SET @product_read_index_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'product'
    AND index_name = 'idx_product_updated_id'
);
SET @product_read_index_ddl := IF(
  @product_read_index_exists = 0,
  'ALTER TABLE product ADD KEY idx_product_updated_id (updated_at DESC,id DESC), ALGORITHM=INPLACE, LOCK=NONE',
  'SELECT 1'
);
PREPARE product_read_index_statement FROM @product_read_index_ddl;
EXECUTE product_read_index_statement;
DEALLOCATE PREPARE product_read_index_statement;

SET @product_sku_read_index_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'product_sku'
    AND index_name = 'idx_product_id_id'
);
SET @product_sku_read_index_ddl := IF(
  @product_sku_read_index_exists = 0,
  'ALTER TABLE product_sku ADD KEY idx_product_id_id (product_id,id), ALGORITHM=INPLACE, LOCK=NONE',
  'SELECT 1'
);
PREPARE product_sku_read_index_statement FROM @product_sku_read_index_ddl;
EXECUTE product_sku_read_index_statement;
DEALLOCATE PREPARE product_sku_read_index_statement;
