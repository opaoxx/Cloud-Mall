package com.cloudmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 余额扣款共享归属数据访问接口。 */
@Mapper
public interface BalanceDebitAttemptMapper extends BaseMapper<BalanceDebitAttemptPO> {
  /** 创建共享归属表及其唯一互斥约束。 */
  @Insert(
      "CREATE TABLE IF NOT EXISTS balance_debit_attempt (id BIGINT PRIMARY KEY,pay_no VARCHAR(64)"
          + " NOT NULL,operation_key VARCHAR(128) NOT NULL,mode VARCHAR(16) NOT NULL,xid"
          + " VARCHAR(128),ledger_key VARCHAR(128) NOT NULL,branch_id BIGINT,user_id BIGINT NOT"
          + " NULL,order_no VARCHAR(64) NOT NULL,amount DECIMAL(18,2) NOT NULL,state VARCHAR(16)"
          + " NOT NULL,created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,UNIQUE KEY"
          + " uk_balance_attempt_pay_no(pay_no),UNIQUE KEY"
          + " uk_balance_attempt_operation(operation_key),UNIQUE KEY"
          + " uk_balance_attempt_xid(xid),UNIQUE KEY"
          + " uk_balance_attempt_branch(xid,branch_id),UNIQUE KEY"
          + " uk_balance_attempt_ledger(ledger_key),KEY"
          + " idx_balance_attempt_user_state(user_id,state,updated_at))")
  void ensureTable();

  /** 按支付号锁定共享归属。 */
  @Select("select * from balance_debit_attempt where pay_no=#{payNo} for update")
  BalanceDebitAttemptPO selectByPayNoForUpdate(@Param("payNo") String payNo);

  /** 按支付号读取共享归属；缺失时不申请唯一键间隙锁。 */
  @Select("select * from balance_debit_attempt where pay_no=#{payNo}")
  BalanceDebitAttemptPO selectByPayNo(@Param("payNo") String payNo);

  /** 按操作键锁定共享归属。 */
  @Select("select * from balance_debit_attempt where operation_key=#{operationKey} for update")
  BalanceDebitAttemptPO selectByOperationKeyForUpdate(@Param("operationKey") String operationKey);

  /** 按分支标识锁定共享归属。 */
  @Select(
      "select * from balance_debit_attempt where xid=#{xid} and branch_id=#{branchId} for update")
  BalanceDebitAttemptPO selectByBranchForUpdate(
      @Param("xid") String xid, @Param("branchId") Long branchId);

  /** 按预期状态原子推进共享归属，调用方必须校验恰好一行。 */
  @Update(
      "update balance_debit_attempt set state=#{targetState},updated_at=#{updatedAt} where"
          + " pay_no=#{payNo} and state=#{expectedState}")
  int transitionState(
      @Param("payNo") String payNo,
      @Param("expectedState") String expectedState,
      @Param("targetState") String targetState,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
