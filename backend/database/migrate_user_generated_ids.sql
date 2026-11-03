-- Explicitly select the reviewed database; this file never switches databases.
-- Stop User writers and take/verify backups of both tables before deployment.
-- mysql --default-character-set=utf8mb4 -uroot -proot <selected_database> < this_file
-- This revision is validated only in an owned cm_user_ids_* test schema.
-- DDL commits implicitly: a partial failure is not a transaction rollback.
SET @cm_user_ids_stats_expiry := @@SESSION.information_schema_stats_expiry;
SET SESSION information_schema_stats_expiry = 0;
DROP PROCEDURE IF EXISTS cm_migrate_user_address_ids;
DELIMITER $$
CREATE PROCEDURE cm_migrate_user_address_ids()
BEGIN
  DECLARE user_max BIGINT;
  DECLARE address_max BIGINT;
  DECLARE user_next BIGINT;
  DECLARE address_next BIGINT;
  DECLARE safe_limit BIGINT DEFAULT 9007199254740927;

  -- Validate both tables before the first ALTER, including attributes we must retain.
  IF DATABASE() IS NULL OR
    (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema=DATABASE() AND table_name IN ('mall_user','user_address')
        AND table_type='BASE TABLE' AND engine='InnoDB') <> 2 OR
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name IN ('mall_user','user_address')
        AND column_name='id' AND data_type='bigint' AND column_type NOT LIKE '%unsigned%'
        AND is_nullable='NO' AND column_key='PRI' AND column_default IS NULL
        AND extra IN ('','auto_increment') AND column_comment='' AND generation_expression='') <> 2 OR
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name IN ('mall_user','user_address')
        AND index_name='PRIMARY') <> 2 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='USER_IDENTIFIER_SCHEMA_INCOMPATIBLE';
  END IF;
  IF EXISTS (
    SELECT 1 FROM information_schema.key_column_usage
      WHERE (referenced_table_schema=DATABASE()
        AND referenced_table_name IN ('mall_user','user_address') AND referenced_column_name='id')
        OR (table_schema=DATABASE() AND table_name IN ('mall_user','user_address')
          AND column_name='id' AND referenced_table_name IS NOT NULL)
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='USER_IDENTIFIER_FOREIGN_KEY_REQUIRES_REVIEW';
  END IF;

  SELECT COALESCE(MAX(id),0) INTO user_max FROM mall_user;
  SELECT COALESCE(MAX(id),0) INTO address_max FROM user_address;
  SELECT auto_increment INTO user_next FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name='mall_user';
  SELECT auto_increment INTO address_next FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name='user_address';
  IF user_max >= safe_limit OR address_max >= safe_limit OR
    EXISTS (SELECT 1 FROM mall_user WHERE id <= 0) OR
    EXISTS (SELECT 1 FROM user_address WHERE id <= 0) OR
    (user_next IS NULL AND EXISTS (SELECT 1 FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='mall_user'
        AND column_name='id' AND extra='auto_increment')) OR
    (address_next IS NULL AND EXISTS (SELECT 1 FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='user_address'
        AND column_name='id' AND extra='auto_increment')) OR
    (user_next IS NOT NULL AND (user_next <= 0 OR user_next > safe_limit)) OR
    (address_next IS NOT NULL AND (address_next <= 0 OR address_next > safe_limit)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='USER_IDENTIFIER_SAFE_RANGE_INSUFFICIENT';
  END IF;

  -- Do not lower or reset counters of tables that are already compatible.
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='mall_user'
      AND column_name='id' AND extra='auto_increment') THEN
    ALTER TABLE mall_user MODIFY id BIGINT NOT NULL AUTO_INCREMENT;
  END IF;
  SELECT auto_increment INTO user_next FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name='mall_user';
  IF user_next IS NULL OR user_next <= user_max OR user_next > safe_limit THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='USER_IDENTIFIER_USER_POSTCHECK_FAILED';
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='user_address'
      AND column_name='id' AND extra='auto_increment') THEN
    ALTER TABLE user_address MODIFY id BIGINT NOT NULL AUTO_INCREMENT;
  END IF;
  SELECT auto_increment INTO address_next FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name='user_address';
  IF address_next IS NULL OR address_next <= address_max OR address_next > safe_limit THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='USER_IDENTIFIER_ADDRESS_POSTCHECK_FAILED';
  END IF;
END$$
DELIMITER ;
CALL cm_migrate_user_address_ids();
DROP PROCEDURE cm_migrate_user_address_ids;
SET SESSION information_schema_stats_expiry = @cm_user_ids_stats_expiry;
