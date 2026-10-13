package com.cloudmall.pay.mapper;

import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 支付扣款意图、恢复租约和状态的唯一数据库访问入口。 */
@Repository
public class PayDebitIntentMapper {
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

  /** 先筛选两表均为 LEGACY 的到期意图再限制批量，避免隔离记录占满恢复窗口。 */
  public List<PayDebitIntentPO> findDue(LocalDateTime now, int limit) {
    return jdbcTemplate.query(
        "SELECT intent.* FROM pay_debit_intent intent JOIN pay_record payment ON"
            + " payment.pay_no=intent.pay_no WHERE BINARY intent.debit_mode='LEGACY' AND BINARY"
            + " payment.debit_mode='LEGACY' AND intent.status IN"
            + " ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING') AND"
            + " intent.next_retry_at<=? AND (intent.lease_until IS NULL OR intent.lease_until<=?)"
            + " ORDER BY intent.next_retry_at,intent.id LIMIT ?",
        ROW_MAPPER,
        now,
        now,
        Math.max(1, limit));
  }

  /** 恢复器有界主键轮转读取到期候选；null下界从头读，不改旧时间优先扫描。 */
  public List<PayDebitIntentPO> findDueAfter(LocalDateTime now, Long afterId, int limit) {
    return jdbcTemplate.query(
        "SELECT intent.* FROM pay_debit_intent intent JOIN pay_record payment ON"
            + " payment.pay_no=intent.pay_no WHERE BINARY intent.debit_mode='LEGACY' AND BINARY"
            + " payment.debit_mode='LEGACY' AND intent.status IN"
            + " ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING') AND"
            + " intent.next_retry_at<=? AND (intent.lease_until IS NULL OR intent.lease_until<=?)"
            + " AND (? IS NULL OR intent.id>?) ORDER BY intent.id LIMIT ?",
        ROW_MAPPER,
        now,
        now,
        afterId,
        afterId,
        Math.max(1, limit));
  }

  /** 仅在支付单与意图均为 LEGACY 时条件领取恢复任务。 */
  public int claim(String payNo, String owner, LocalDateTime leaseUntil, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE pay_debit_intent SET lease_owner=?,lease_until=?,attempts=attempts+1,updated_at=?"
            + " WHERE pay_no=? AND BINARY debit_mode='LEGACY'"
            + " AND EXISTS (SELECT 1 FROM pay_record"
            + " WHERE pay_record.pay_no=pay_debit_intent.pay_no"
            + " AND BINARY pay_record.debit_mode='LEGACY') AND status IN"
            + " ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING')"
            + " AND next_retry_at<=? AND (lease_until IS NULL OR lease_until<=?)",
        owner,
        leaseUntil,
        now,
        payNo,
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

  /** Legacy终态需当前未过期owner；已有退款意图只能结束为已退，调用者必须核一行。 */
  public int finishOwnedLegacy(String payNo, String claimToken, String status, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE pay_debit_intent SET"
            + " status=?,last_error=NULL,lease_owner=NULL,lease_until=NULL,next_retry_at=?,updated_at=?"
            + " WHERE BINARY pay_no=BINARY ? AND BINARY lease_owner=BINARY ? AND lease_until>? AND"
            + " BINARY debit_mode='LEGACY' AND status IN"
            + " ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING') AND"
            + " (status<>'REFUND_PENDING' OR BINARY ?='REFUNDED')",
        status,
        now.plusYears(100),
        now,
        payNo,
        claimToken,
        now,
        status);
  }

  /** 保留扣款事实和原业务键，记录失败后按退避时间重试。 */
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
        error == null ? null : error.substring(0, Math.min(512, error.length())),
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

  /** Legacy重试仅清当前未过期租约；已有退款意图仅接受退款方向，拒绝时零行。 */
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
            + " BINARY payment.debit_mode='LEGACY') AND (status<>'REFUND_PENDING' OR BINARY"
            + " ?='REFUND_PENDING')",
        status,
        error == null ? null : error.substring(0, Math.min(512, error.length())),
        nextRetryAt,
        now,
        payNo,
        claimToken,
        now,
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

  /** 将 JDBC 行映射为准确的支付意图持久化对象。 */
  private static final RowMapper<PayDebitIntentPO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        PayDebitIntentPO intent = new PayDebitIntentPO();
        intent.id = resultSet.getLong("id");
        intent.payNo = resultSet.getString("pay_no");
        intent.orderNo = resultSet.getString("order_no");
        intent.userId = resultSet.getLong("user_id");
        intent.amount = resultSet.getObject("amount", BigDecimal.class);
        intent.status = resultSet.getString("status");
        intent.debitMode = resultSet.getString("debit_mode");
        intent.attempts = resultSet.getInt("attempts");
        intent.nextRetryAt = resultSet.getTimestamp("next_retry_at").toLocalDateTime();
        intent.leaseOwner = resultSet.getString("lease_owner");
        Timestamp leaseUntil = resultSet.getTimestamp("lease_until");
        intent.leaseUntil = leaseUntil == null ? null : leaseUntil.toLocalDateTime();
        intent.lastError = resultSet.getString("last_error");
        intent.createdAt = resultSet.getTimestamp("created_at").toLocalDateTime();
        intent.updatedAt = resultSet.getTimestamp("updated_at").toLocalDateTime();
        return intent;
      };
}
