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

  /** 锁定原扣款对应的退款总额，供退款事务校验。 */
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
