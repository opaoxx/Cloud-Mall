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
            + "attempts INT NOT NULL DEFAULT 0,next_retry_at DATETIME(3) NOT NULL,"
            + "lease_owner VARCHAR(128),lease_until DATETIME(3),last_error VARCHAR(512),"
            + "created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,"
            + "KEY idx_pay_debit_recovery(status,next_retry_at,lease_until),"
            + "KEY idx_pay_debit_order(order_no))");
  }

  /** 在远程扣款之前写入不可变 INIT 意图。 */
  public int insertIfAbsent(PayDebitIntentPO intent) {
    return jdbcTemplate.update(
        "INSERT IGNORE INTO pay_debit_intent(id,pay_no,order_no,user_id,amount,status,attempts,"
            + "next_retry_at,created_at,updated_at) VALUES(?,?,?,?,?,'INIT',0,?,?,?)",
        intent.id,
        intent.payNo,
        intent.orderNo,
        intent.userId,
        intent.amount,
        intent.nextRetryAt,
        intent.createdAt,
        intent.updatedAt);
  }

  /** 按支付号读取持久化意图。 */
  public PayDebitIntentPO findByPayNo(String payNo) {
    List<PayDebitIntentPO> rows =
        jdbcTemplate.query(
            "SELECT * FROM pay_debit_intent WHERE pay_no=?", ROW_MAPPER, payNo);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 查找可恢复且没有有效租约的支付意图。 */
  public List<PayDebitIntentPO> findDue(LocalDateTime now, int limit) {
    return jdbcTemplate.query(
        "SELECT * FROM pay_debit_intent WHERE status IN"
            + " ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING')"
            + " AND next_retry_at<=? AND (lease_until IS NULL OR lease_until<=?)"
            + " ORDER BY next_retry_at,id LIMIT ?",
        ROW_MAPPER,
        now,
        now,
        Math.max(1, limit));
  }

  /** 使用条件更新领取一条支付恢复任务。 */
  public int claim(String payNo, String owner, LocalDateTime leaseUntil, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE pay_debit_intent SET lease_owner=?,lease_until=?,attempts=attempts+1,updated_at=?"
            + " WHERE pay_no=? AND status IN"
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
