package com.cloudmall.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.pay.domain.po.PayTccReceiptPO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Pay receipt 语义化数据访问接口。 */
@Mapper
public interface PayTccReceiptMapper extends BaseMapper<PayTccReceiptPO> {
  /** 验证被拒分支没有取得本地 receipt 所有权。 */
  @Select("SELECT COUNT(*) FROM pay_tcc_receipt WHERE xid=#{xid} AND branch_id=#{branchId}")
  int countByBranch(@Param("xid") String xid, @Param("branchId") Long branchId);

  /** 当前读找出不可变唯一键的原所有者，避免复用旧快照。 */
  @Select(
      "SELECT * FROM pay_tcc_receipt WHERE pay_no=#{payNo} OR xid=#{xid} OR"
          + " operation_key=#{operationKey} OR ledger_key=#{ledgerKey} FOR UPDATE")
  java.util.List<PayTccReceiptPO> findOwners(
      @Param("payNo") String payNo,
      @Param("xid") String xid,
      @Param("operationKey") String operationKey,
      @Param("ledgerKey") String ledgerKey);

  /** 幂等创建 Pay receipt 表。 */
  @Update(
      "CREATE TABLE IF NOT EXISTS pay_tcc_receipt (id BIGINT PRIMARY KEY,pay_no VARCHAR(64) NOT"
          + " NULL,xid VARCHAR(128) NOT NULL,user_id BIGINT NOT NULL,order_no VARCHAR(64) NOT"
          + " NULL,amount DECIMAL(18,2) NOT NULL,operation_key VARCHAR(128) NOT NULL,ledger_key"
          + " VARCHAR(128) NOT NULL,branch_id BIGINT,state VARCHAR(16) NOT NULL,created_at"
          + " DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,UNIQUE KEY"
          + " uk_pay_receipt_pay(pay_no),UNIQUE KEY uk_pay_receipt_xid(xid),UNIQUE KEY"
          + " uk_pay_receipt_operation(operation_key),UNIQUE KEY"
          + " uk_pay_receipt_ledger(ledger_key))")
  void ensureTable();

  /** 按支付号锁定 receipt。 */
  @Select("SELECT * FROM pay_tcc_receipt WHERE pay_no=#{payNo} FOR UPDATE")
  PayTccReceiptPO selectByPayNoForUpdate(@Param("payNo") String payNo);

  /** 按支付号读取 receipt。 */
  @Select("SELECT * FROM pay_tcc_receipt WHERE pay_no=#{payNo}")
  PayTccReceiptPO selectByPayNo(@Param("payNo") String payNo);

  /** 首次插入 CLAIMED receipt，返回唯一 winner 的影响行数。 */
  @Insert(
      "INSERT IGNORE INTO"
          + " pay_tcc_receipt(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at)"
          + " VALUES(#{id},#{payNo},#{xid},#{userId},#{orderNo},#{amount},#{operationKey},#{ledgerKey},'CLAIMED',#{createdAt},#{updatedAt})")
  int claim(PayTccReceiptPO receipt);

  /** 绑定唯一 Pay branch 并推进 CLAIMED/TRYING 状态。 */
  @Update(
      "UPDATE pay_tcc_receipt SET"
          + " branch_id=#{branchId},state=#{targetState},updated_at=#{updatedAt} WHERE"
          + " pay_no=#{payNo} AND xid=#{xid} AND state=#{expectedState} AND branch_id IS NULL")
  int bindAndTransition(
      @Param("payNo") String payNo,
      @Param("xid") String xid,
      @Param("branchId") Long branchId,
      @Param("expectedState") String expectedState,
      @Param("targetState") String targetState,
      @Param("updatedAt") LocalDateTime updatedAt);

  /** 按当前状态 CAS 推进终态。 */
  @Update(
      "UPDATE pay_tcc_receipt SET state=#{targetState},updated_at=#{updatedAt} WHERE"
          + " pay_no=#{payNo} AND xid=#{xid} AND branch_id=#{branchId} AND state=#{expectedState}")
  int transitionState(
      @Param("payNo") String payNo,
      @Param("xid") String xid,
      @Param("branchId") Long branchId,
      @Param("expectedState") String expectedState,
      @Param("targetState") String targetState,
      @Param("updatedAt") LocalDateTime updatedAt);
}
