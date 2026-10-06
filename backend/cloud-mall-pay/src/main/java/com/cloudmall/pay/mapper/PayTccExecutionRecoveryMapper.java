package com.cloudmall.pay.mapper;

import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Pay coordinator 有界恢复扫描和 lease 语义数据访问接口。 */
@Mapper
public interface PayTccExecutionRecoveryMapper {
  /** 按恢复到期、原支付 tuple 和决议方向筛选候选，LIMIT 在全部过滤之后执行。 */
  @Select(
      "SELECT execution.* FROM pay_tcc_execution execution JOIN pay_record payment ON"
          + " payment.pay_no = execution.pay_no WHERE BINARY payment.debit_mode='TCC' AND BINARY"
          + " payment.pay_no = BINARY execution.pay_no AND payment.user_id=execution.user_id AND"
          + " BINARY payment.order_no=BINARY execution.order_no AND payment.amount=execution.amount"
          + " AND BINARY execution.operation_key = BINARY CONCAT('tcc-debit:',execution.pay_no) AND"
          + " BINARY execution.ledger_key = BINARY execution.pay_no AND ((BINARY execution.state IN"
          + " ('RUNNING','UNKNOWN') AND BINARY execution.decision='NONE') OR (BINARY"
          + " execution.state='COMMIT_REQUESTED' AND BINARY execution.decision='COMMIT') OR (BINARY"
          + " execution.state='ROLLBACK_REQUESTED' AND BINARY execution.decision='ROLLBACK')) AND"
          + " execution.xid IS NOT NULL AND LENGTH(TRIM(execution.xid))>0 AND"
          + " execution.next_recovery_at <= #{now} AND (execution.recovery_lease_until IS NULL OR"
          + " execution.recovery_lease_until <= #{now}) ORDER BY"
          + " execution.next_recovery_at,execution.id LIMIT #{limit}")
  List<PayTccExecutionPO> findDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

  /** 原 tuple、状态、决议和 lease 到期条件全部满足时认领恢复任务。 */
  @Update(
      "UPDATE pay_tcc_execution execution JOIN pay_record payment ON payment.pay_no ="
          + " execution.pay_no SET"
          + " execution.recovery_owner=#{token},execution.recovery_lease_until=#{leaseUntil},"
          + " execution.recovery_attempts=LEAST(execution.recovery_attempts+1,31) WHERE BINARY"
          + " payment.debit_mode='TCC' AND BINARY payment.pay_no = BINARY execution.pay_no AND"
          + " payment.user_id=execution.user_id AND BINARY payment.order_no=BINARY"
          + " execution.order_no AND payment.amount=execution.amount AND BINARY"
          + " execution.operation_key = BINARY CONCAT('tcc-debit:',execution.pay_no) AND BINARY"
          + " execution.ledger_key = BINARY execution.pay_no AND ((BINARY execution.state IN"
          + " ('RUNNING','UNKNOWN') AND BINARY execution.decision='NONE') OR (BINARY"
          + " execution.state='COMMIT_REQUESTED' AND BINARY execution.decision='COMMIT') OR (BINARY"
          + " execution.state='ROLLBACK_REQUESTED' AND BINARY execution.decision='ROLLBACK')) AND"
          + " execution.xid IS NOT NULL AND LENGTH(TRIM(execution.xid))>0 AND"
          + " execution.next_recovery_at <= #{now} AND (execution.recovery_lease_until IS NULL OR"
          + " execution.recovery_lease_until <= #{now}) AND execution.pay_no=#{payNo} AND BINARY"
          + " execution.pay_no=BINARY #{payNo}")
  int claimRecovery(
      @Param("payNo") String payNo,
      @Param("token") String token,
      @Param("leaseUntil") LocalDateTime leaseUntil,
      @Param("now") LocalDateTime now);

  /** 只按 token 释放恢复 lease 并更新退避元数据，不改变业务 execution 字段。 */
  @Update(
      "UPDATE pay_tcc_execution SET recovery_owner=NULL,recovery_lease_until=NULL,"
          + " next_recovery_at=#{nextAt},recovery_last_error=#{error} WHERE pay_no=#{payNo}"
          + " AND recovery_owner=#{token}")
  int finishRecovery(
      @Param("payNo") String payNo,
      @Param("token") String token,
      @Param("nextAt") LocalDateTime nextAt,
      @Param("error") String error);

  /** 逐列检查并幂等补充恢复调度字段，列名和定义仅来自固定白名单。 */
  default void ensureSchema() {
    String[][] columns = {
      {"recovery_attempts", "INT NOT NULL DEFAULT 0"},
      {"next_recovery_at", "DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)"},
      {"recovery_owner", "VARCHAR(128)"},
      {"recovery_lease_until", "DATETIME(3)"},
      {"recovery_last_error", "VARCHAR(512)"}
    };
    for (String[] column : columns) {
      if (columnExists(column[0]) == 0) {
        try {
          addColumn(column[0], column[1]);
        } catch (RuntimeException migrationFailure) {
          if (columnExists(column[0]) == 0) {
            throw migrationFailure;
          }
        }
      }
    }
  }

  /** 检查 execution 表指定列是否存在。 */
  @Select(
      "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"
          + " AND table_name='pay_tcc_execution' AND column_name=#{columnName}")
  int columnExists(@Param("columnName") String columnName);

  /** 使用内部固定白名单执行单列迁移。 */
  @Update("ALTER TABLE pay_tcc_execution ADD COLUMN ${columnName} ${columnDefinition}")
  int addColumn(
      @Param("columnName") String columnName, @Param("columnDefinition") String columnDefinition);
}
