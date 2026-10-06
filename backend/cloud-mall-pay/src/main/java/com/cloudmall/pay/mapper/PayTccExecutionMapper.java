package com.cloudmall.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Pay coordinator 执行事实语义化数据访问接口。 */
@Mapper
public interface PayTccExecutionMapper extends BaseMapper<PayTccExecutionPO> {
  /** 幂等创建 coordinator execution 表。 */
  @Update(
      "CREATE TABLE IF NOT EXISTS pay_tcc_execution (id BIGINT PRIMARY KEY,pay_no VARCHAR(64) NOT"
          + " NULL,xid VARCHAR(128),user_id BIGINT NOT NULL,order_no VARCHAR(64) NOT NULL,amount"
          + " DECIMAL(18,2) NOT NULL,operation_key VARCHAR(128) NOT NULL,ledger_key VARCHAR(128)"
          + " NOT NULL,state VARCHAR(32) NOT NULL,decision VARCHAR(16) NOT NULL,last_error"
          + " VARCHAR(512),created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,UNIQUE"
          + " KEY uk_pay_tcc_execution_pay(pay_no),UNIQUE KEY uk_pay_tcc_execution_xid(xid),UNIQUE"
          + " KEY uk_pay_tcc_execution_operation(operation_key),UNIQUE KEY"
          + " uk_pay_tcc_execution_ledger(ledger_key))")
  void ensureTable();

  /** 按支付号读取执行事实。 */
  @Select("SELECT * FROM pay_tcc_execution WHERE pay_no=#{payNo}")
  PayTccExecutionPO selectByPayNo(@Param("payNo") String payNo);

  /** 按支付号锁定执行事实。 */
  @Select("SELECT * FROM pay_tcc_execution WHERE pay_no=#{payNo} FOR UPDATE")
  PayTccExecutionPO selectForUpdate(@Param("payNo") String payNo);

  /** 仅首次将无 XID CLAIMED execution 绑定为 RUNNING。 */
  @Update(
      "UPDATE pay_tcc_execution SET xid=#{xid},state='RUNNING',updated_at=#{now} WHERE"
          + " pay_no=#{payNo} AND xid IS NULL AND decision='NONE' AND state IN"
          + " ('CLAIMED','BEGIN_UNKNOWN')")
  int bindXid(
      @Param("payNo") String payNo, @Param("xid") String xid, @Param("now") LocalDateTime now);

  /** 仅在决议仍为 NONE 时写入单向决议和目标状态。 */
  @Update(
      "UPDATE pay_tcc_execution SET decision=#{decision},state=#{state},updated_at=#{now} WHERE"
          + " pay_no=#{payNo} AND xid=#{xid} AND decision='NONE'")
  int setDecision(
      @Param("payNo") String payNo,
      @Param("xid") String xid,
      @Param("decision") String decision,
      @Param("state") String state,
      @Param("now") LocalDateTime now);

  /** 仅将绑定 XID 且尚未选定方向的未决 execution CAS 为 ROLLBACK_REQUESTED。 */
  @Update(
      "UPDATE pay_tcc_execution SET"
          + " decision='ROLLBACK',state='ROLLBACK_REQUESTED',updated_at=#{now},next_recovery_at=LEAST(next_recovery_at,#{now})"
          + " WHERE BINARY pay_no=BINARY #{payNo} AND BINARY xid=BINARY #{xid} AND BINARY"
          + " decision='NONE' AND BINARY state IN ('RUNNING','UNKNOWN')")
  int requestRollback(
      @Param("payNo") String payNo, @Param("xid") String xid, @Param("now") LocalDateTime now);

  /** 更新非终态观察结果，终态和冲突状态不可被回退。 */
  @Update(
      "UPDATE pay_tcc_execution SET state=#{state},last_error=#{lastError},updated_at=#{now} WHERE"
          + " pay_no=#{payNo} AND state NOT IN ('COMMITTED','ROLLEDBACK','CONFLICT')")
  int updateObservation(
      @Param("payNo") String payNo,
      @Param("state") String state,
      @Param("lastError") String lastError,
      @Param("now") LocalDateTime now);
}
