-- Mark only confirmed hmall catalog snapshots; safe to run again on an existing volume.
SET NAMES utf8mb4;
USE cloudmall_product;
SET @source_type_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='cloudmall_product' AND table_name='product_hot_stat'
    AND column_name='source_type');
SET @source_type_ddl := IF(@source_type_exists=0,
  'ALTER TABLE cloudmall_product.product_hot_stat ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT ''UNKNOWN''',
  'SELECT 1');
PREPARE source_type_statement FROM @source_type_ddl;
EXECUTE source_type_statement;
DEALLOCATE PREPARE source_type_statement;

SET @source_ref_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='cloudmall_product' AND table_name='product_hot_stat'
    AND column_name='source_ref');
SET @source_ref_ddl := IF(@source_ref_exists=0,
  'ALTER TABLE cloudmall_product.product_hot_stat ADD COLUMN source_ref VARCHAR(128) NULL',
  'SELECT 1');
PREPARE source_ref_statement FROM @source_ref_ddl;
EXECUTE source_ref_statement;
DEALLOCATE PREPARE source_ref_statement;

-- Reject incompatible pre-existing columns before changing any provenance values.
DROP PROCEDURE IF EXISTS cm21_assert_hot_source_columns;
DELIMITER $$
CREATE PROCEDURE cm21_assert_hot_source_columns()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='cloudmall_product' AND table_name='product_hot_stat'
      AND column_name='source_type' AND data_type='varchar'
      AND character_maximum_length=32 AND is_nullable='NO'
      AND column_default='UNKNOWN'
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='product_hot_stat.source_type schema mismatch';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='cloudmall_product' AND table_name='product_hot_stat'
      AND column_name='source_ref' AND data_type='varchar'
      AND character_maximum_length=128 AND is_nullable='YES'
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='product_hot_stat.source_ref schema mismatch';
  END IF;
END$$
DELIMITER ;
CALL cm21_assert_hot_source_columns();
DROP PROCEDURE cm21_assert_hot_source_columns;

-- A row is historical only when its values still exactly match the imported source row.
UPDATE cloudmall_product.product_hot_stat AS hot
JOIN `hm-item`.item AS source_item ON source_item.id=hot.product_id
SET hot.source_type='LEGACY_IMPORT',hot.source_ref='hm-item.item'
WHERE hot.source_type='UNKNOWN' AND hot.source_ref IS NULL
  AND hot.view_count=COALESCE(source_item.sold,0)
  AND hot.search_count=COALESCE(source_item.comment_count,0)
  AND hot.hot_score=COALESCE(source_item.sold,0);

SELECT source_type,COUNT(*) AS rows_count FROM cloudmall_product.product_hot_stat
GROUP BY source_type;
