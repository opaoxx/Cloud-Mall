-- Run before starting Order consumers, using an explicitly selected database.
-- Example: mysql --default-character-set=utf8mb4 -uroot -proot cloudmall_order < this_file
-- Additive and repeatable; historical raw_payload stays NULL. Never backfill from JSON.
SET @cm17cn_raw_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='seckill_order_dedup_conflict'
    AND column_name='raw_payload');
SET @cm17cn_raw_ddl := IF(@cm17cn_raw_exists=0,
  'ALTER TABLE seckill_order_dedup_conflict ADD COLUMN raw_payload LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER payload',
  'SELECT 1');
PREPARE cm17cn_raw_statement FROM @cm17cn_raw_ddl;
EXECUTE cm17cn_raw_statement;
DEALLOCATE PREPARE cm17cn_raw_statement;

DROP PROCEDURE IF EXISTS cm17cn_assert_conflict_raw;
DELIMITER $$
CREATE PROCEDURE cm17cn_assert_conflict_raw()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='seckill_order_dedup_conflict'
      AND column_name='raw_payload' AND data_type='longtext' AND is_nullable='YES'
      AND character_set_name='utf8mb4' AND collation_name='utf8mb4_bin'
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='seckill_order_dedup_conflict.raw_payload schema mismatch';
  END IF;
END$$
DELIMITER ;
CALL cm17cn_assert_conflict_raw();
DROP PROCEDURE cm17cn_assert_conflict_raw;
