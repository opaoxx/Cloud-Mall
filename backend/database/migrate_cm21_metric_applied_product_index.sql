-- Explicit manual CM21 e851 index migration. Not an init/startup/bootstrap script.
-- Stop related product instances/writers and drain their transactions/leases first.
-- Use a dedicated clean autocommit connection with an explicitly selected non-system schema.
-- Abort the ENTIRE script on the first error; never use a client's continue-on-error/--force mode.
-- Auxiliary CREATE/DROP and target ALTER implicitly commit; SQL ROLLBACK cannot undo this script.
-- This next-only statement rejects an already active transaction (1568) BEFORE auxiliary DDL.
-- Do not replace it with SET SESSION TRANSACTION, which permits execution inside a transaction.
SET TRANSACTION READ WRITE;

DELIMITER $$
CREATE PROCEDURE cm21_metric_applied_index_e851_migrate()
SQL SECURITY INVOKER
MODIFIES SQL DATA
BEGIN
  DECLARE selected_schema VARCHAR(64);
  DECLARE caller_grantee VARCHAR(300);
  DECLARE original_lock_wait_timeout BIGINT UNSIGNED DEFAULT NULL;
  DECLARE restore_failed BOOLEAN DEFAULT FALSE;
  DECLARE phase_number INT DEFAULT 0;
  DECLARE metadata_count BIGINT DEFAULT 0;
  DECLARE matching_count BIGINT DEFAULT 0;
  DECLARE current_layout VARCHAR(4);
  DECLARE original_layout VARCHAR(4);
  DECLARE final_status VARCHAR(32);
  DECLARE definition_current JSON;
  DECLARE definition_original JSON;
  DECLARE table_definition JSON;
  DECLARE column_definition JSON;
  DECLARE check_definition JSON;
  DECLARE preserved_index_definition JSON;
  DECLARE data_checksum_current CHAR(64);
  DECLARE data_checksum_original CHAR(64);
  DECLARE data_rows_current BIGINT UNSIGNED DEFAULT 0;
  DECLARE data_rows_original BIGINT UNSIGNED DEFAULT 0;

  -- A restore failure on a still-live connection must not replace the original server condition.
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    BEGIN
      DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET restore_failed = TRUE;
      IF original_lock_wait_timeout IS NOT NULL THEN
        SET SESSION lock_wait_timeout = original_lock_wait_timeout;
      END IF;
    END;
    RESIGNAL;
  END;

  SET selected_schema = DATABASE();
  IF selected_schema IS NULL OR LOWER(selected_schema) IN
      ('mysql', 'sys', 'information_schema', 'performance_schema') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CM21_INDEX_EXPLICIT_SCHEMA_REQUIRED';
  END IF;
  IF @@SESSION.autocommit <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CM21_INDEX_DEDICATED_AUTOCOMMIT_REQUIRED';
  END IF;
  -- Ten seconds bounds session MDL waits only inside this CALL, including the target ALTER.
  -- Auxiliary CREATE/DROP are outside this setting; the operator's process deadline bounds them.
  SET original_lock_wait_timeout = @@SESSION.lock_wait_timeout;
  SET SESSION lock_wait_timeout = 10;

  -- Conservative supported visibility path for this project's root/root maintenance connection.
  -- This is NOT a general MySQL least-privilege algorithm. Role/schema-only grants or partial
  -- revokes are unsupported here; never GRANT, mutate authentication or infer hidden metadata.
  SET caller_grantee = CONCAT(QUOTE(SUBSTRING_INDEX(CURRENT_USER(), '@', 1)),
      '@', QUOTE(SUBSTRING_INDEX(CURRENT_USER(), '@', -1)));
  SELECT COUNT(DISTINCT privilege_type) INTO metadata_count
  FROM information_schema.user_privileges
  WHERE BINARY grantee = BINARY caller_grantee
    AND privilege_type IN ('SELECT', 'SHOW VIEW', 'TRIGGER');
  IF metadata_count <> 3 OR @@GLOBAL.partial_revokes <> 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CM21_INDEX_METADATA_VISIBILITY_UNSUPPORTED';
  END IF;

  -- The same complete checks run both before any target ALTER and after its completion/NOOP.
  WHILE phase_number < 2 DO
    SELECT COUNT(*) INTO metadata_count FROM information_schema.tables
    WHERE table_schema = selected_schema AND BINARY table_name = BINARY 'product_metric_applied_event'
      AND table_type = 'BASE TABLE' AND engine = 'InnoDB'
      AND SUBSTRING_INDEX(table_collation, '_', 1) = 'utf8mb4';
    IF metadata_count <> 1 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CM21_INDEX_BASE_TABLE_MISMATCH';
    END IF;
    SELECT JSON_OBJECT('tableType', table_type, 'engine', engine, 'rowFormat', row_format,
        'tableCollation', table_collation, 'createOptions', create_options,
        'tableComment', table_comment, 'autoIncrement', auto_increment)
    INTO table_definition FROM information_schema.tables
    WHERE table_schema = selected_schema AND BINARY table_name = BINARY 'product_metric_applied_event';

    SELECT COUNT(*), COALESCE(SUM(CASE WHEN c.column_default IS NULL AND c.extra = ''
        AND COALESCE(c.generation_expression, '') = '' AND c.srs_id IS NULL
        THEN CASE c.ordinal_position
        WHEN 1 THEN BINARY c.column_name = BINARY 'event_id'
          AND REPLACE(REPLACE(LOWER(c.column_type), 'bigint(20)', 'bigint'), 'int(11)', 'int') = 'char(64)'
          AND c.is_nullable = 'NO' AND BINARY c.character_set_name = BINARY 'ascii'
          AND BINARY c.collation_name = BINARY 'ascii_bin'
        WHEN 2 THEN BINARY c.column_name = BINARY 'fingerprint'
          AND REPLACE(REPLACE(LOWER(c.column_type), 'bigint(20)', 'bigint'), 'int(11)', 'int') = 'char(64)'
          AND c.is_nullable = 'NO' AND BINARY c.character_set_name = BINARY 'ascii'
          AND BINARY c.collation_name = BINARY 'ascii_bin'
        WHEN 3 THEN BINARY c.column_name = BINARY 'event_type'
          AND REPLACE(REPLACE(LOWER(c.column_type), 'bigint(20)', 'bigint'), 'int(11)', 'int') = 'varchar(32)'
          AND c.is_nullable = 'NO' AND BINARY c.character_set_name = BINARY 'ascii'
          AND BINARY c.collation_name = BINARY 'ascii_bin'
        WHEN 4 THEN BINARY c.column_name = BINARY 'product_id'
          AND REPLACE(REPLACE(LOWER(c.column_type), 'bigint(20)', 'bigint'), 'int(11)', 'int') = 'bigint'
          AND c.is_nullable = 'NO' AND c.character_set_name IS NULL
          AND c.collation_name IS NULL
        WHEN 5 THEN BINARY c.column_name = BINARY 'sku_id'
          AND REPLACE(REPLACE(LOWER(c.column_type), 'bigint(20)', 'bigint'), 'int(11)', 'int') = 'bigint'
          AND c.is_nullable = 'YES' AND c.character_set_name IS NULL
          AND c.collation_name IS NULL
        WHEN 6 THEN BINARY c.column_name = BINARY 'quantity'
          AND REPLACE(REPLACE(LOWER(c.column_type), 'bigint(20)', 'bigint'), 'int(11)', 'int') = 'int'
          AND c.is_nullable = 'NO' AND c.character_set_name IS NULL
          AND c.collation_name IS NULL
        WHEN 7 THEN BINARY c.column_name = BINARY 'order_no'
          AND REPLACE(REPLACE(LOWER(c.column_type), 'bigint(20)', 'bigint'), 'int(11)', 'int') = 'varchar(64)'
          AND c.is_nullable = 'YES' AND BINARY c.character_set_name = BINARY 'utf8mb4'
          AND BINARY c.collation_name = BINARY 'utf8mb4_bin'
        WHEN 8 THEN BINARY c.column_name = BINARY 'source_ref'
          AND REPLACE(REPLACE(LOWER(c.column_type), 'bigint(20)', 'bigint'), 'int(11)', 'int') = 'varchar(160)'
          AND c.is_nullable = 'NO' AND BINARY c.character_set_name = BINARY 'utf8mb4'
          AND BINARY c.collation_name = BINARY 'utf8mb4_bin'
        WHEN 9 THEN BINARY c.column_name = BINARY 'source_revision'
          AND REPLACE(REPLACE(LOWER(c.column_type), 'bigint(20)', 'bigint'), 'int(11)', 'int') = 'bigint'
          AND c.is_nullable = 'YES' AND c.character_set_name IS NULL
          AND c.collation_name IS NULL
        WHEN 10 THEN BINARY c.column_name = BINARY 'occurred_at'
          AND REPLACE(REPLACE(LOWER(c.column_type), 'bigint(20)', 'bigint'), 'int(11)', 'int') = 'datetime(3)'
          AND c.is_nullable = 'NO' AND c.character_set_name IS NULL
          AND c.collation_name IS NULL
        WHEN 11 THEN BINARY c.column_name = BINARY 'applied_at'
          AND REPLACE(REPLACE(LOWER(c.column_type), 'bigint(20)', 'bigint'), 'int(11)', 'int') = 'datetime(3)'
          AND c.is_nullable = 'NO' AND c.character_set_name IS NULL
          AND c.collation_name IS NULL
        WHEN 12 THEN BINARY c.column_name = BINARY 'metric_version'
          AND REPLACE(REPLACE(LOWER(c.column_type), 'bigint(20)', 'bigint'), 'int(11)', 'int') = 'bigint'
          AND c.is_nullable = 'NO' AND c.character_set_name IS NULL
          AND c.collation_name IS NULL
        ELSE FALSE END ELSE FALSE END), 0)
    INTO metadata_count, matching_count FROM information_schema.columns c
    WHERE c.table_schema = selected_schema AND BINARY c.table_name = BINARY 'product_metric_applied_event';
    IF metadata_count <> 12 OR matching_count <> 12 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CM21_INDEX_COLUMNS_MISMATCH';
    END IF;
    SELECT JSON_OBJECTAGG(c.column_name, JSON_OBJECT('ordinalPosition', c.ordinal_position,
        'columnDefault', c.column_default, 'isNullable', c.is_nullable, 'dataType', c.data_type,
        'characterMaximumLength', c.character_maximum_length,
        'characterOctetLength', c.character_octet_length, 'numericPrecision', c.numeric_precision,
        'numericScale', c.numeric_scale, 'datetimePrecision', c.datetime_precision,
        'characterSetName', c.character_set_name, 'collationName', c.collation_name,
        'columnType', c.column_type, 'columnKey', c.column_key, 'extra', c.extra,
        'privileges', c.privileges, 'columnComment', c.column_comment,
        'generationExpression', c.generation_expression, 'srsId', c.srs_id))
    INTO column_definition FROM information_schema.columns c
    WHERE c.table_schema = selected_schema AND BINARY c.table_name = BINARY 'product_metric_applied_event';

    SELECT COUNT(*) INTO metadata_count FROM information_schema.table_constraints t
    WHERE t.table_schema = selected_schema AND BINARY t.table_name = BINARY 'product_metric_applied_event'
      AND t.constraint_type = 'CHECK';
    SELECT COUNT(*) INTO matching_count FROM information_schema.table_constraints t
    JOIN information_schema.check_constraints c ON c.constraint_schema = t.constraint_schema
      AND BINARY c.constraint_name = BINARY t.constraint_name
    WHERE t.table_schema = selected_schema AND BINARY t.table_name = BINARY 'product_metric_applied_event'
      AND t.constraint_type = 'CHECK' AND BINARY t.constraint_name = BINARY 'ck_metric_applied'
      AND t.enforced = 'YES'
      AND REGEXP_REPLACE(LOWER(REPLACE(c.check_clause, '`', '')), '[[:space:]]', '') =
        '((product_id>0)and(quantity>0)and(metric_version>0)and((sku_idisnull)or(sku_id>0))and((source_revisionisnull)or(source_revision>=0)))';
    IF metadata_count <> 1 OR matching_count <> 1 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CM21_INDEX_CHECK_MISMATCH';
    END IF;
    SELECT JSON_OBJECTAGG(t.constraint_name,
        JSON_OBJECT('constraintType', t.constraint_type, 'enforced', t.enforced, 'clause', c.check_clause))
    INTO check_definition FROM information_schema.table_constraints t
    JOIN information_schema.check_constraints c ON c.constraint_schema = t.constraint_schema
      AND BINARY c.constraint_name = BINARY t.constraint_name
    WHERE t.table_schema = selected_schema AND BINARY t.table_name = BINARY 'product_metric_applied_event'
      AND t.constraint_type = 'CHECK';

    SELECT COUNT(*), COALESCE(SUM(CASE WHEN s.index_type = 'BTREE' AND s.is_visible = 'YES'
        AND s.collation = 'A' AND s.sub_part IS NULL AND s.expression IS NULL
        AND ((BINARY s.index_name = BINARY 'PRIMARY' AND s.non_unique = 0
              AND s.seq_in_index = 1 AND BINARY s.column_name = BINARY 'event_id')
          OR (BINARY s.index_name = BINARY 'uk_metric_applied_version' AND s.non_unique = 0
              AND ((s.seq_in_index = 1 AND BINARY s.column_name = BINARY 'product_id')
                OR (s.seq_in_index = 2 AND BINARY s.column_name = BINARY 'metric_version')))
          OR (BINARY s.index_name = BINARY 'idx_metric_applied_product' AND s.non_unique = 1
              AND s.index_comment = '' AND s.comment = ''
              AND ((s.seq_in_index = 1 AND BINARY s.column_name = BINARY 'product_id')
                OR (s.seq_in_index = 2 AND BINARY s.column_name = BINARY 'metric_version'))))
        THEN 1 ELSE 0 END), 0)
    INTO metadata_count, matching_count FROM information_schema.statistics s
    WHERE s.table_schema = selected_schema AND BINARY s.table_name = BINARY 'product_metric_applied_event';
    IF matching_count <> metadata_count OR metadata_count NOT IN (3, 5)
        OR (SELECT COUNT(*) FROM information_schema.statistics s
            WHERE s.table_schema = selected_schema AND BINARY s.table_name = BINARY 'product_metric_applied_event'
              AND BINARY s.index_name = BINARY 'PRIMARY') <> 1
        OR (SELECT COUNT(*) FROM information_schema.statistics s
            WHERE s.table_schema = selected_schema AND BINARY s.table_name = BINARY 'product_metric_applied_event'
              AND BINARY s.index_name = BINARY 'uk_metric_applied_version') <> 2
        OR (SELECT COUNT(*) FROM information_schema.statistics s
            WHERE s.table_schema = selected_schema AND BINARY s.table_name = BINARY 'product_metric_applied_event'
              AND BINARY s.index_name = BINARY 'idx_metric_applied_product') <> metadata_count - 3 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CM21_INDEX_LAYOUT_OR_ATTRIBUTES_MISMATCH';
    END IF;
    SET current_layout = IF(metadata_count = 5, 'OLD3', 'NEW2');
    SELECT JSON_OBJECTAGG(CONCAT(s.index_name, '#', s.seq_in_index),
        JSON_OBJECT('name', s.index_name, 'nonUnique', s.non_unique, 'sequence', s.seq_in_index,
        'columnName', s.column_name, 'collation', s.collation, 'subPart', s.sub_part,
        'packed', s.packed, 'nullable', s.nullable, 'indexType', s.index_type,
        'comment', s.comment, 'indexComment', s.index_comment,
        'visible', s.is_visible, 'expression', s.expression))
    INTO preserved_index_definition FROM information_schema.statistics s
    WHERE s.table_schema = selected_schema AND BINARY s.table_name = BINARY 'product_metric_applied_event'
      AND BINARY s.index_name <> BINARY 'idx_metric_applied_product';

    -- Global metadata visibility is required above, so inbound and outbound cross-schema FKs
    -- cannot disappear merely because the current schema differs from the referencing schema.
    IF EXISTS (SELECT 1 FROM information_schema.key_column_usage k
        WHERE k.referenced_table_name IS NOT NULL
          AND ((k.table_schema = selected_schema AND BINARY k.table_name = BINARY 'product_metric_applied_event')
            OR (k.referenced_table_schema = selected_schema
              AND BINARY k.referenced_table_name = BINARY 'product_metric_applied_event'))) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CM21_INDEX_FOREIGN_KEY_DEPENDENCY';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.triggers t
        WHERE (t.event_object_schema = selected_schema
            AND BINARY t.event_object_table = BINARY 'product_metric_applied_event')
          OR (t.trigger_schema NOT IN ('mysql', 'sys', 'information_schema', 'performance_schema')
            AND REGEXP_LIKE(REPLACE(t.action_statement, '`', ''),
              '(^|[^0-9a-z_$])product_metric_applied_event([^0-9a-z_$]|$)', 'i'))) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CM21_INDEX_TRIGGER_DEPENDENCY';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.view_table_usage v
        WHERE v.table_schema = selected_schema AND BINARY v.table_name = BINARY 'product_metric_applied_event') THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CM21_INDEX_VIEW_DEPENDENCY';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.routines r
        WHERE r.routine_schema NOT IN ('mysql', 'sys', 'information_schema', 'performance_schema')
          AND NOT (r.routine_schema = selected_schema AND r.routine_type = 'PROCEDURE'
            AND BINARY r.routine_name = BINARY 'cm21_metric_applied_index_e851_migrate')
          AND r.routine_definition IS NULL) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CM21_INDEX_METADATA_VISIBILITY_UNSUPPORTED';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.routines r
        WHERE r.routine_schema NOT IN ('mysql', 'sys', 'information_schema', 'performance_schema')
          AND NOT (r.routine_schema = selected_schema AND r.routine_type = 'PROCEDURE'
            AND BINARY r.routine_name = BINARY 'cm21_metric_applied_index_e851_migrate')
          AND REGEXP_LIKE(REPLACE(r.routine_definition, '`', ''),
            '(^|[^0-9a-z_$])product_metric_applied_event([^0-9a-z_$]|$)', 'i')) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CM21_INDEX_ROUTINE_DEPENDENCY';
    END IF;
    -- Text detection is deliberately conservative, not a general dependency parser. Dynamic SQL,
    -- external clients/scripts and scheduled events still require the operator's reviewed inventory.
    SET definition_current = JSON_OBJECT('table', table_definition, 'columns', column_definition,
        'checks', check_definition, 'preservedIndexes', preserved_index_definition);

    -- Read only: canonical ordered typed row serialization, constant-size rolling SHA256.
    -- This is a before/after audit, not a lock that stops an uncoordinated writer.
    SET data_checksum_current = SHA2('CM21_METRIC_APPLIED_TYPED_ROWS_V1', 256);
    SET data_rows_current = 0;
    BEGIN
      DECLARE cursor_finished BOOLEAN DEFAULT FALSE;
      DECLARE serialized_row LONGTEXT CHARACTER SET utf8mb4;
      DECLARE applied_rows CURSOR FOR
        SELECT CAST(JSON_ARRAY(event_id, fingerprint, event_type, product_id, sku_id, quantity,
            order_no, source_ref, source_revision, occurred_at, applied_at, metric_version)
            AS CHAR CHARACTER SET utf8mb4)
        FROM product_metric_applied_event ORDER BY event_id;
      DECLARE CONTINUE HANDLER FOR NOT FOUND SET cursor_finished = TRUE;
      OPEN applied_rows;
      row_scan: LOOP
        FETCH applied_rows INTO serialized_row;
        IF cursor_finished THEN LEAVE row_scan; END IF;
        SET data_checksum_current = SHA2(CONCAT(data_checksum_current, ':',
            OCTET_LENGTH(serialized_row), ':', serialized_row), 256);
        SET data_rows_current = data_rows_current + 1;
      END LOOP;
      CLOSE applied_rows;
    END;

    IF phase_number = 0 THEN
      SET original_layout = current_layout;
      SET definition_original = definition_current;
      SET data_checksum_original = data_checksum_current;
      SET data_rows_original = data_rows_current;
      IF current_layout = 'OLD3' THEN
        ALTER TABLE product_metric_applied_event DROP INDEX idx_metric_applied_product,
            ALGORITHM=INPLACE, LOCK=NONE;
        SET final_status = 'MIGRATED_TARGET';
      ELSE
        SET final_status = 'NOOP_TARGET';
      END IF;
    ELSE
      IF current_layout <> 'NEW2' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CM21_INDEX_POSTCONDITION_LAYOUT_MISMATCH';
      END IF;
      IF NOT (CAST(definition_original AS CHAR CHARACTER SET utf8mb4)
          <=> CAST(definition_current AS CHAR CHARACTER SET utf8mb4))
          OR NOT (data_checksum_original <=> data_checksum_current)
          OR data_rows_original <> data_rows_current THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CM21_INDEX_POSTCONDITION_CONTENT_CHANGED';
      END IF;
    END IF;
    SET phase_number = phase_number + 1;
  END WHILE;

  SET SESSION lock_wait_timeout = original_lock_wait_timeout;
  SELECT final_status AS migration_status, original_layout AS state_before,
      current_layout AS state_after, definition_original AS definition_before,
      definition_current AS definition_after, data_rows_original AS rows_before,
      data_rows_current AS rows_after, data_checksum_original AS data_sha256_before,
      data_checksum_current AS data_sha256_after,
      @@SESSION.lock_wait_timeout AS lock_wait_timeout_restored;
END$$
DELIMITER ;
-- CREATE collision must abort before CALL: never DROP an unknown same-name routine.
-- A failed CALL deliberately leaves this known freshly-created helper for explicit owned cleanup.
CALL cm21_metric_applied_index_e851_migrate();
DROP PROCEDURE cm21_metric_applied_index_e851_migrate;
