package com.cloudmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 用户余额账本数据访问接口。 */
@Mapper
public interface BalanceLedgerMapper extends BaseMapper<BalanceLedgerPO> {
  /** 保留同支付号全部类型/状态/关联键的CI原行，100加一哨兵，不锁定或修复。 */
  @Select("SELECT * FROM balance_ledger WHERE pay_no=#{payNo} ORDER BY id LIMIT 101")
  java.util.List<BalanceLedgerPO> findPaymentLedgerCandidates(@Param("payNo") String payNo);

  /** 保留原扣款键的CI关联候选及坏原行，100加一哨兵，不锁定或过滤退款类型。 */
  @Select(
      "SELECT * FROM balance_ledger WHERE original_operation_key=#{originalOperationKey}"
          + " ORDER BY id LIMIT 101")
  java.util.List<BalanceLedgerPO> findLinkedRefundCandidates(
      @Param("originalOperationKey") String originalOperationKey);

  /** 只读核操作键全局CI候选数量，防止遗留重复或别名被摘要去重隐藏。 */
  @Select("SELECT COUNT(*) FROM balance_ledger WHERE operation_key=#{operationKey}")
  Long countOperationCandidates(@Param("operationKey") String operationKey);

  /** 为已有本地数据库幂等创建余额账本。 */
  @Update(
      "CREATE TABLE IF NOT EXISTS balance_ledger (id BIGINT PRIMARY KEY,user_id BIGINT NOT"
          + " NULL,operation_key VARCHAR(128) NOT NULL UNIQUE,operation_type VARCHAR(16) NOT"
          + " NULL,original_operation_key VARCHAR(128),pay_no VARCHAR(64) NOT NULL,order_no"
          + " VARCHAR(64) NOT NULL,amount DECIMAL(18,2) NOT NULL,state VARCHAR(16) NOT"
          + " NULL,balance_before DECIMAL(18,2) NOT NULL,balance_after DECIMAL(18,2) NOT"
          + " NULL,created_at DATETIME(3) NOT NULL,KEY idx_balance_order(order_no,created_at),KEY"
          + " idx_balance_original(original_operation_key))")
  void ensureTable();

  /** 按唯一操作键读取账本记录。 */
  @Select("select * from balance_ledger where operation_key=#{operationKey}")
  BalanceLedgerPO selectByOperationKey(@Param("operationKey") String operationKey);

  /** 读取已应用退款的SQL总额；不加锁，也不证明关联行完整性。 */
  @Select(
      "select coalesce(sum(amount),0) from balance_ledger"
          + " where original_operation_key=#{originalOperationKey}"
          + " and operation_type='REFUND' and state='APPLIED'")
  BigDecimal sumAppliedRefunds(@Param("originalOperationKey") String originalOperationKey);

  /** 查询原扣款记录并锁定其用户账本行。 */
  @Select(
      "select * from balance_ledger where operation_key=#{operationKey}"
          + " and operation_type='DEBIT' for update")
  BalanceLedgerPO selectDebitForUpdate(@Param("operationKey") String operationKey);

  /** 按支付号读取 Legacy 扣款事实，不对不存在的唯一值申请间隙锁。 */
  @Select(
      "select * from balance_ledger where pay_no=#{payNo} and operation_type='DEBIT' and"
          + " state='APPLIED'")
  BalanceLedgerPO selectAppliedDebitByPayNo(@Param("payNo") String payNo);

  /** 已确认重放的同pay扣款范围；保留CI/全状态候选，两行足以证明不唯一，不锁不写。 */
  @Select(
      "SELECT * FROM balance_ledger WHERE pay_no=#{payNo} AND operation_type='DEBIT' ORDER BY id"
          + " LIMIT 2")
  java.util.List<BalanceLedgerPO> findDebitCandidatesByPayNo(@Param("payNo") String payNo);

  /** 按支付号读取任意状态的 Legacy 扣款事实。 */
  @Select("select * from balance_ledger where pay_no=#{payNo} and operation_type='DEBIT'")
  BalanceLedgerPO selectDebitByPayNo(@Param("payNo") String payNo);

  /** 查询退款操作关联的原扣款键。 */
  @Select(
      "select operation_key from balance_ledger where"
          + " original_operation_key=#{originalOperationKey} and operation_type='REFUND' and"
          + " state='APPLIED' limit 1")
  String selectFirstRefundOperationKey(@Param("originalOperationKey") String originalOperationKey);
}
