package com.cloudmall.pay.mapper;

import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 支付扣款意图、恢复租约和状态的唯一数据库访问入口。 */
@Repository
public class PayDebitIntentMapper {
  /** 匹配至少一个非Java17空白码点；精确复用isBlank集合，保留NBSP等非blank字符。 */
  private static final String NON_BLANK_RECOVERY_KEY_PATTERN =
      "[^\\x{0009}-\\x{000D}\\x{001C}-\\x{0020}\\x{1680}\\x{2000}-\\x{2006}"
          + "\\x{2008}-\\x{200A}\\x{2028}-\\x{2029}\\x{205F}\\x{3000}]";

  /** 执行参数化 MySQL SQL 的模板。 */
  private final JdbcTemplate jdbcTemplate;

  /** 创建支付扣款意图 Mapper。 */
  public PayDebitIntentMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 为已有本地 MySQL 数据卷幂等准备支付扣款意图表。 */
  public void ensureTable() {
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS pay_debit_intent ("
            + "id BIGINT PRIMARY KEY,pay_no VARCHAR(64) NOT NULL UNIQUE,"
            + "order_no VARCHAR(64) NOT NULL UNIQUE,user_id BIGINT NOT NULL,"
            + "amount DECIMAL(18,2) NOT NULL,status VARCHAR(32) NOT NULL,"
            + "debit_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY',"
            + "attempts INT NOT NULL DEFAULT 0,next_retry_at DATETIME(3) NOT NULL,"
            + "lease_owner VARCHAR(128),lease_until DATETIME(3),last_error VARCHAR(512),"
            + "created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,"
            + "KEY idx_pay_debit_recovery(status,next_retry_at,lease_until),"
            + "KEY idx_pay_debit_order(order_no))");
    ensureDebitModeColumn("pay_record");
    ensureDebitModeColumn("pay_debit_intent");
  }

  /** 只允许固定的两张 Pay 表进行幂等模式迁移，既有行由默认值归入 LEGACY。 */
  private void ensureDebitModeColumn(String table) {
    if (!java.util.Set.of("pay_record", "pay_debit_intent").contains(table))
      throw new IllegalArgumentException("未允许的支付模式迁移表");
    String query =
        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"
            + " AND table_name=? AND column_name='debit_mode'";
    if (jdbcTemplate.queryForObject(query, Integer.class, table) > 0) return;
    try {
      jdbcTemplate.execute(
          "ALTER TABLE " + table + " ADD COLUMN debit_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY'");
    } catch (org.springframework.dao.DataAccessException migrationFailure) {
      if (jdbcTemplate.queryForObject(query, Integer.class, table) == 0) throw migrationFailure;
    }
  }

  /** 在远程扣款之前写入不可变 INIT 意图。 */
  public int insertIfAbsent(PayDebitIntentPO intent) {
    return jdbcTemplate.update(
        "INSERT IGNORE INTO"
            + " pay_debit_intent(id,pay_no,order_no,user_id,amount,debit_mode,status,attempts,next_retry_at,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,'INIT',0,?,?,?)",
        intent.id,
        intent.payNo,
        intent.orderNo,
        intent.userId,
        intent.amount,
        intent.debitMode,
        intent.nextRetryAt,
        intent.createdAt,
        intent.updatedAt);
  }

  /** 按支付号读取持久化意图。 */
  public PayDebitIntentPO findByPayNo(String payNo) {
    List<PayDebitIntentPO> rows =
        jdbcTemplate.query("SELECT * FROM pay_debit_intent WHERE pay_no=?", ROW_MAPPER, payNo);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 按支付号执行当前锁定读，供首次意图与失败授权使用同一锁序。 */
  public PayDebitIntentPO findByPayNoForUpdate(String payNo) {
    List<PayDebitIntentPO> rows =
        jdbcTemplate.query(
            "SELECT * FROM pay_debit_intent WHERE pay_no=? FOR UPDATE", ROW_MAPPER, payNo);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 先过滤零年月日、超出月末及原计数、键、载荷资格不符的候选，再限制批量。 */
  public List<PayDebitIntentPO> findDue(LocalDateTime now, int limit) {
    return jdbcTemplate.query(
        "SELECT intent.* FROM pay_debit_intent intent JOIN pay_record payment ON"
            + " BINARY payment.pay_no=BINARY intent.pay_no AND payment.user_id=intent.user_id AND"
            + " BINARY payment.order_no=BINARY intent.order_no AND payment.amount=intent.amount"
            + " WHERE REGEXP_LIKE(payment.pay_no,?,'c') AND REGEXP_LIKE(payment.order_no,?,'c')"
            + " AND payment.user_id>0 AND payment.amount>0 AND BINARY"
            + " intent.debit_mode='LEGACY' AND BINARY"
            + " payment.debit_mode='LEGACY' AND payment.status IN"
            + " ('PENDING','ORDER_SYNC_UNKNOWN','REFUND_PENDING') AND intent.status IN"
            + " ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING') AND"
            + " intent.attempts>=0"
            + " AND YEAR(intent.next_retry_at)>=1000 AND MONTH(intent.next_retry_at)>0"
            + " AND DAYOFMONTH(intent.next_retry_at)>0"
            + " AND DAYOFMONTH(intent.next_retry_at)<=DAYOFMONTH(LAST_DAY(intent.next_retry_at))"
            + " AND YEAR(intent.created_at)>=1000 AND MONTH(intent.created_at)>0"
            + " AND DAYOFMONTH(intent.created_at)>0"
            + " AND DAYOFMONTH(intent.created_at)<=DAYOFMONTH(LAST_DAY(intent.created_at))"
            + " AND YEAR(intent.updated_at)>=1000 AND MONTH(intent.updated_at)>0"
            + " AND DAYOFMONTH(intent.updated_at)>0"
            + " AND DAYOFMONTH(intent.updated_at)<=DAYOFMONTH(LAST_DAY(intent.updated_at))"
            + " AND (intent.lease_until IS NULL OR (YEAR(intent.lease_until)>=1000"
            + " AND MONTH(intent.lease_until)>0 AND DAYOFMONTH(intent.lease_until)>0"
            + " AND DAYOFMONTH(intent.lease_until)<=DAYOFMONTH(LAST_DAY(intent.lease_until))))"
            + " AND intent.next_retry_at<=? AND (intent.lease_until IS NULL OR"
            + " intent.lease_until<=?)"
            + " ORDER BY intent.next_retry_at,intent.id LIMIT ?",
        ROW_MAPPER,
        NON_BLANK_RECOVERY_KEY_PATTERN,
        NON_BLANK_RECOVERY_KEY_PATTERN,
        now,
        now,
        Math.max(1, limit));
  }

  /** 主键轮转过滤零年月日和超出月末的日期；null从头读，保原资格、时间与批量。 */
  public List<PayDebitIntentPO> findDueAfter(LocalDateTime now, Long afterId, int limit) {
    return jdbcTemplate.query(
        "SELECT intent.* FROM pay_debit_intent intent JOIN pay_record payment ON"
            + " BINARY payment.pay_no=BINARY intent.pay_no AND payment.user_id=intent.user_id AND"
            + " BINARY payment.order_no=BINARY intent.order_no AND payment.amount=intent.amount"
            + " WHERE REGEXP_LIKE(payment.pay_no,?,'c') AND REGEXP_LIKE(payment.order_no,?,'c')"
            + " AND payment.user_id>0 AND payment.amount>0 AND BINARY"
            + " intent.debit_mode='LEGACY' AND BINARY"
            + " payment.debit_mode='LEGACY' AND payment.status IN"
            + " ('PENDING','ORDER_SYNC_UNKNOWN','REFUND_PENDING') AND intent.status IN"
            + " ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING') AND"
            + " intent.attempts>=0"
            + " AND YEAR(intent.next_retry_at)>=1000 AND MONTH(intent.next_retry_at)>0"
            + " AND DAYOFMONTH(intent.next_retry_at)>0"
            + " AND DAYOFMONTH(intent.next_retry_at)<=DAYOFMONTH(LAST_DAY(intent.next_retry_at))"
            + " AND YEAR(intent.created_at)>=1000 AND MONTH(intent.created_at)>0"
            + " AND DAYOFMONTH(intent.created_at)>0"
            + " AND DAYOFMONTH(intent.created_at)<=DAYOFMONTH(LAST_DAY(intent.created_at))"
            + " AND YEAR(intent.updated_at)>=1000 AND MONTH(intent.updated_at)>0"
            + " AND DAYOFMONTH(intent.updated_at)>0"
            + " AND DAYOFMONTH(intent.updated_at)<=DAYOFMONTH(LAST_DAY(intent.updated_at))"
            + " AND (intent.lease_until IS NULL OR (YEAR(intent.lease_until)>=1000"
            + " AND MONTH(intent.lease_until)>0 AND DAYOFMONTH(intent.lease_until)>0"
            + " AND DAYOFMONTH(intent.lease_until)<=DAYOFMONTH(LAST_DAY(intent.lease_until))))"
            + " AND intent.next_retry_at<=? AND (intent.lease_until IS NULL OR"
            + " intent.lease_until<=?)"
            + " AND (? IS NULL OR intent.id>?) ORDER BY intent.id LIMIT ?",
        ROW_MAPPER,
        NON_BLANK_RECOVERY_KEY_PATTERN,
        NON_BLANK_RECOVERY_KEY_PATTERN,
        now,
        now,
        afterId,
        afterId,
        Math.max(1, limit));
  }

  /** 年月日下界、月末上界及原资格通过才领取，INT上限饱和；拒绝不改意图。 */
  public int claim(String payNo, String owner, LocalDateTime leaseUntil, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE pay_debit_intent SET lease_owner=?,lease_until=?,attempts=CASE WHEN"
            + " attempts<2147483647 THEN attempts+1 ELSE attempts END,updated_at=? WHERE BINARY"
            + " pay_debit_intent.pay_no=BINARY ? AND attempts>=0 AND BINARY debit_mode='LEGACY' AND"
            + " EXISTS (SELECT 1 FROM pay_record WHERE BINARY pay_record.pay_no=BINARY"
            + " pay_debit_intent.pay_no AND pay_record.user_id=pay_debit_intent.user_id AND BINARY"
            + " pay_record.order_no=BINARY pay_debit_intent.order_no AND"
            + " pay_record.amount=pay_debit_intent.amount AND pay_record.user_id>0 AND"
            + " pay_record.amount>0 AND REGEXP_LIKE(pay_record.pay_no,?,'c') AND"
            + " REGEXP_LIKE(pay_record.order_no,?,'c') AND BINARY pay_record.debit_mode='LEGACY'"
            + " AND pay_record.status IN ('PENDING','ORDER_SYNC_UNKNOWN','REFUND_PENDING')) AND"
            + " status IN ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING') AND"
            + " YEAR(next_retry_at)>=1000 AND MONTH(next_retry_at)>0 AND"
            + " DAYOFMONTH(next_retry_at)>0 AND YEAR(created_at)>=1000 AND MONTH(created_at)>0 AND"
            + " DAYOFMONTH(created_at)>0 AND YEAR(updated_at)>=1000 AND MONTH(updated_at)>0 AND"
            + " DAYOFMONTH(updated_at)>0 AND (lease_until IS NULL OR (YEAR(lease_until)>=1000 AND"
            + " MONTH(lease_until)>0 AND DAYOFMONTH(lease_until)>0"
            + " AND DAYOFMONTH(lease_until)<=DAYOFMONTH(LAST_DAY(lease_until))))"
            + " AND DAYOFMONTH(next_retry_at)<=DAYOFMONTH(LAST_DAY(next_retry_at))"
            + " AND DAYOFMONTH(created_at)<=DAYOFMONTH(LAST_DAY(created_at))"
            + " AND DAYOFMONTH(updated_at)<=DAYOFMONTH(LAST_DAY(updated_at))"
            + " AND next_retry_at<=? AND"
            + " (lease_until IS NULL OR lease_until<=?)",
        owner,
        leaseUntil,
        now,
        payNo,
        NON_BLANK_RECOVERY_KEY_PATTERN,
        NON_BLANK_RECOVERY_KEY_PATTERN,
        now,
        now);
  }

  /** 租约持有者在远程调用前落盘中间状态。 */
  public int updateOwnedStatus(String payNo, String owner, String status, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE pay_debit_intent SET status=?,updated_at=?"
            + " WHERE pay_no=? AND lease_owner=? AND status IN"
            + " ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING')",
        status,
        now,
        payNo,
        owner);
  }

  /** 将已确认的成功、失败或退款终态写入意图并释放租约。 */
  public int finish(String payNo, String owner, String status, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE pay_debit_intent SET status=?,last_error=NULL,lease_owner=NULL,lease_until=NULL,"
            + "next_retry_at=?,updated_at=? WHERE pay_no=? AND lease_owner=?",
        status,
        now.plusYears(100),
        now,
        payNo,
        owner);
  }

  /** Legacy终态需当前owner；FAILED仅初始意图、退款仅已退目标，调用者必须核一行。 */
  public int finishOwnedLegacy(String payNo, String claimToken, String status, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE pay_debit_intent SET"
            + " status=?,last_error=NULL,lease_owner=NULL,lease_until=NULL,next_retry_at=?,updated_at=?"
            + " WHERE BINARY pay_no=BINARY ? AND BINARY lease_owner=BINARY ? AND lease_until>? AND"
            + " BINARY debit_mode='LEGACY' AND status IN"
            + " ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING') AND"
            + " (status<>'REFUND_PENDING' OR BINARY ?='REFUNDED') AND (BINARY ?<>'FAILED' OR"
            + " status='INIT')",
        status,
        now.plusYears(100),
        now,
        payNo,
        claimToken,
        now,
        status,
        status);
  }

  /** 保留扣款事实和原业务键，错误取512完整码点，按退避时间重试。 */
  public int recordRetry(
      String payNo,
      String owner,
      String status,
      String error,
      LocalDateTime nextRetryAt,
      LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE pay_debit_intent SET status=?,last_error=?,next_retry_at=?,"
            + "lease_owner=NULL,lease_until=NULL,updated_at=? WHERE pay_no=? AND lease_owner=?",
        status,
        truncateRetryError(error),
        nextRetryAt,
        now,
        payNo,
        owner);
  }

  /** Legacy中间推进需当前owner、未过期租约及双方模式；已有退款意图不可回退。 */
  public int updateOwnedLegacyStatus(
      String payNo, String claimToken, String status, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE pay_debit_intent SET status=?,updated_at=? WHERE BINARY pay_no=BINARY ? AND BINARY"
            + " lease_owner=BINARY ? AND lease_until>? AND BINARY debit_mode='LEGACY' AND status IN"
            + " ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING') AND EXISTS (SELECT 1 FROM"
            + " pay_record payment WHERE BINARY payment.pay_no=BINARY pay_debit_intent.pay_no AND"
            + " BINARY payment.debit_mode='LEGACY') AND (status<>'REFUND_PENDING' OR BINARY"
            + " ?='REFUND_PENDING')",
        status,
        now,
        payNo,
        claimToken,
        now,
        status);
  }

  /** Legacy重试需当前租约；保原阶段门，错误取512完整码点，拒绝时零行。 */
  public int recordRetryOwnedLegacy(
      String payNo,
      String claimToken,
      String status,
      String error,
      LocalDateTime nextRetryAt,
      LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE pay_debit_intent SET"
            + " status=?,last_error=?,next_retry_at=?,lease_owner=NULL,lease_until=NULL,updated_at=?"
            + " WHERE BINARY pay_no=BINARY ? AND BINARY lease_owner=BINARY ? AND lease_until>? AND"
            + " BINARY debit_mode='LEGACY' AND status IN"
            + " ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING') AND EXISTS (SELECT 1 FROM"
            + " pay_record payment WHERE BINARY payment.pay_no=BINARY pay_debit_intent.pay_no AND"
            + " BINARY payment.debit_mode='LEGACY' AND (BINARY ?<>'INIT' OR"
            + " payment.status='PENDING')) AND (status<>'REFUND_PENDING' OR BINARY"
            + " ?='REFUND_PENDING') AND (BINARY ?<>'INIT' OR status='INIT')",
        status,
        truncateRetryError(error),
        nextRetryAt,
        now,
        payNo,
        claimToken,
        now,
        status,
        status,
        status);
  }

  /** 远端写前只读核当前资格；该观察不保证检查之后到RPC发出之间的原子性。 */
  public boolean hasOwnedLegacyLease(String payNo, String claimToken, LocalDateTime now) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pay_debit_intent intent JOIN pay_record payment ON BINARY"
                + " intent.pay_no=BINARY payment.pay_no WHERE BINARY intent.pay_no=BINARY ? AND"
                + " BINARY intent.lease_owner=BINARY ? AND intent.lease_until>? AND BINARY"
                + " intent.debit_mode='LEGACY' AND BINARY payment.debit_mode='LEGACY' AND"
                + " intent.status IN ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING')",
            Integer.class,
            payNo,
            claimToken,
            now);
    return Integer.valueOf(1).equals(count);
  }

  /**
   * 写RPC前只读核对当前原载荷、领取资格和操作阶段；不保证查询到远端执行之间的原子性。
   *
   * @param expected 已核对的原意图快照
   * @param claimToken 当前领取令牌
   * @param operation DEBIT、PAID或REFUND写操作
   * @param now 本次核对时间
   * @return 恰好一组原载荷和阶段符合写权限时为true
   */
  public boolean hasOwnedLegacyRpcPermission(
      PayDebitIntentPO expected, String claimToken, String operation, LocalDateTime now) {
    Integer matchingRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pay_debit_intent intent JOIN pay_record payment ON BINARY"
                + " intent.pay_no=BINARY payment.pay_no WHERE BINARY intent.pay_no=BINARY ?"
                + " AND BINARY intent.lease_owner=BINARY ? AND intent.lease_until>?"
                + " AND BINARY intent.debit_mode='LEGACY' AND BINARY payment.debit_mode='LEGACY'"
                + " AND payment.user_id=? AND intent.user_id=?"
                + " AND BINARY payment.order_no=BINARY ? AND BINARY intent.order_no=BINARY ?"
                + " AND payment.amount=? AND intent.amount=? AND"
                + " ((BINARY ?='DEBIT' AND BINARY payment.status='PENDING' AND BINARY"
                + " intent.status='INIT') OR (BINARY ?='PAID' AND BINARY"
                + " payment.status='ORDER_SYNC_UNKNOWN' AND BINARY intent.status='DEBITED')"
                + " OR (BINARY ?='REFUND' AND BINARY payment.status='REFUND_PENDING' AND BINARY"
                + " intent.status='REFUND_PENDING'))",
            Integer.class,
            expected.payNo,
            claimToken,
            now,
            expected.userId,
            expected.userId,
            expected.orderNo,
            expected.orderNo,
            expected.amount,
            expected.amount,
            operation,
            operation,
            operation);
    return Integer.valueOf(1).equals(matchingRows);
  }

  /** 按VARCHAR(512)字符容量取完整Unicode码点前缀，不拆合法代理对，不归一化或修复坏文本。 */
  private static String truncateRetryError(String error) {
    if (error == null) return null;
    int retainedCodePoints = Math.min(512, error.codePointCount(0, error.length()));
    int endIndex = error.offsetByCodePoints(0, retainedCodePoints);
    return error.substring(0, endIndex);
  }

  /** 保留boxed及时间SQL NULL；typed时间读取保留合法DATETIME组件，避免混合历转换。 */
  private static final RowMapper<PayDebitIntentPO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        PayDebitIntentPO intent = new PayDebitIntentPO();
        intent.id = resultSet.getObject("id", Long.class);
        intent.payNo = resultSet.getString("pay_no");
        intent.orderNo = resultSet.getString("order_no");
        intent.userId = resultSet.getObject("user_id", Long.class);
        intent.amount = resultSet.getObject("amount", BigDecimal.class);
        intent.status = resultSet.getString("status");
        intent.debitMode = resultSet.getString("debit_mode");
        intent.attempts = resultSet.getObject("attempts", Integer.class);
        intent.nextRetryAt = resultSet.getObject("next_retry_at", LocalDateTime.class);
        intent.leaseOwner = resultSet.getString("lease_owner");
        intent.leaseUntil = resultSet.getObject("lease_until", LocalDateTime.class);
        intent.lastError = resultSet.getString("last_error");
        intent.createdAt = resultSet.getObject("created_at", LocalDateTime.class);
        intent.updatedAt = resultSet.getObject("updated_at", LocalDateTime.class);
        return intent;
      };
}
