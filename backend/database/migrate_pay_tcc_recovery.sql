-- CloudMall Pay TCC coordinator bounded recovery metadata migration.
USE cloudmall_pay;

SET @column_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pay_tcc_execution' AND column_name='recovery_attempts');
SET @migration_sql := IF(@column_exists=0,'ALTER TABLE pay_tcc_execution ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0','SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @column_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pay_tcc_execution' AND column_name='next_recovery_at');
SET @migration_sql := IF(@column_exists=0,'ALTER TABLE pay_tcc_execution ADD COLUMN next_recovery_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)','SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @column_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pay_tcc_execution' AND column_name='recovery_owner');
SET @migration_sql := IF(@column_exists=0,'ALTER TABLE pay_tcc_execution ADD COLUMN recovery_owner VARCHAR(128)','SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @column_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pay_tcc_execution' AND column_name='recovery_lease_until');
SET @migration_sql := IF(@column_exists=0,'ALTER TABLE pay_tcc_execution ADD COLUMN recovery_lease_until DATETIME(3)','SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @column_exists := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pay_tcc_execution' AND column_name='recovery_last_error');
SET @migration_sql := IF(@column_exists=0,'ALTER TABLE pay_tcc_execution ADD COLUMN recovery_last_error VARCHAR(512)','SELECT 1');
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
