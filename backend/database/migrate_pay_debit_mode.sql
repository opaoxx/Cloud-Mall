-- CM-18 stage 12A: freeze the protocol used by each payment and recovery intent.
-- Existing rows remain LEGACY. No balance or payment status is changed.
USE cloudmall_pay;
SET @cm18_record_mode_present = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='pay_record' AND column_name='debit_mode');
SET @cm18_mode_sql = IF(@cm18_record_mode_present=0,
  'ALTER TABLE pay_record ADD COLUMN debit_mode VARCHAR(16) NOT NULL DEFAULT ''LEGACY''',
  'SELECT ''pay_record.debit_mode already exists''');
PREPARE cm18_mode_statement FROM @cm18_mode_sql;
EXECUTE cm18_mode_statement;
DEALLOCATE PREPARE cm18_mode_statement;
SET @cm18_intent_mode_present = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='pay_debit_intent' AND column_name='debit_mode');
SET @cm18_mode_sql = IF(@cm18_intent_mode_present=0,
  'ALTER TABLE pay_debit_intent ADD COLUMN debit_mode VARCHAR(16) NOT NULL DEFAULT ''LEGACY''',
  'SELECT ''pay_debit_intent.debit_mode already exists''');
PREPARE cm18_mode_statement FROM @cm18_mode_sql;
EXECUTE cm18_mode_statement;
DEALLOCATE PREPARE cm18_mode_statement;
