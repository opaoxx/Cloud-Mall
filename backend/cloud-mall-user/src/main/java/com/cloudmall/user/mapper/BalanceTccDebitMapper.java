package com.cloudmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.user.domain.po.BalanceTccDebitPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** TCC 冻结状态数据访问接口。 */
@Mapper
public interface BalanceTccDebitMapper extends BaseMapper<BalanceTccDebitPO> {
  /** 保留两种终态的CI候选，100加一哨兵，精确归属由只读Service验证。 */
  @Select(
      "SELECT * FROM balance_tcc_debit WHERE user_id=#{userId}"
          + " AND state IN ('CONFIRMED','CANCELLED') ORDER BY updated_at,id LIMIT 101")
  java.util.List<BalanceTccDebitPO> findTerminalOwnershipCandidates(@Param("userId") Long userId);

  /** 保留该用户TRYING的CI候选，100加一哨兵，原行由Service精确校验。 */
  @Select(
      "SELECT * FROM balance_tcc_debit WHERE user_id=#{userId} AND state='TRYING'"
          + " ORDER BY updated_at,id LIMIT 101")
  java.util.List<BalanceTccDebitPO> findTryingOwnershipCandidates(@Param("userId") Long userId);

  /** 按用户观察原TRYING总额与非法原行，不过滤坏状态/金额或修改冻结事实。 */
  @Select(
      "SELECT COALESCE(SUM(CASE WHEN BINARY state='TRYING' THEN amount ELSE 0 END),0) AS"
          + " frozen_total, COUNT(CASE WHEN amount IS NULL OR amount<=0 OR state IS NULL OR BINARY"
          + " state NOT IN ('TRYING','CONFIRMED','CANCELLED') THEN 1 END) AS invalid_row_count FROM"
          + " balance_tcc_debit WHERE user_id=#{userId}")
  java.util.Map<String, Object> selectFrozenEvidence(@Param("userId") Long userId);

  /** 创建 TCC 冻结状态表及其幂等约束。 */
  @Insert(
      "CREATE TABLE IF NOT EXISTS balance_tcc_debit (id BIGINT PRIMARY KEY,operation_key"
          + " VARCHAR(128) NOT NULL,xid VARCHAR(128) NOT NULL,branch_id BIGINT NOT NULL,user_id"
          + " BIGINT NOT NULL,pay_no VARCHAR(64) NOT NULL,order_no VARCHAR(64) NOT NULL,amount"
          + " DECIMAL(18,2) NOT NULL,state VARCHAR(16) NOT NULL,balance_before DECIMAL(18,2) NOT"
          + " NULL,balance_after_try DECIMAL(18,2) NOT NULL,created_at DATETIME(3) NOT"
          + " NULL,updated_at DATETIME(3) NOT NULL,UNIQUE KEY"
          + " uk_balance_tcc_operation(operation_key),UNIQUE KEY uk_balance_tcc_xid(xid),UNIQUE KEY"
          + " uk_balance_tcc_branch(xid,branch_id),UNIQUE KEY uk_balance_tcc_pay_no(pay_no),KEY"
          + " idx_balance_tcc_user_state(user_id,state,updated_at))")
  void ensureTable();

  /** 按操作键锁定 TCC 业务行。 */
  @Select("select * from balance_tcc_debit where operation_key=#{operationKey} for update")
  BalanceTccDebitPO selectByOperationKeyForUpdate(@Param("operationKey") String operationKey);

  /** 按支付号读取冻结事实；缺失时不申请间隙锁。 */
  @Select("select * from balance_tcc_debit where pay_no=#{payNo}")
  BalanceTccDebitPO selectByPayNo(@Param("payNo") String payNo);

  /** 按支付号锁定 TCC 业务行。 */
  @Select("select * from balance_tcc_debit where pay_no=#{payNo} for update")
  BalanceTccDebitPO selectByPayNoForUpdate(@Param("payNo") String payNo);

  /** 条件确认冻结行。 */
  @Update(
      "update balance_tcc_debit set state='CONFIRMED',updated_at=#{updatedAt} where"
          + " operation_key=#{operationKey} and state='TRYING'")
  int confirmTrying(
      @Param("operationKey") String operationKey,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);

  /** 条件取消冻结行。 */
  @Update(
      "update balance_tcc_debit set state='CANCELLED',updated_at=#{updatedAt} where"
          + " operation_key=#{operationKey} and state='TRYING'")
  int cancelTrying(
      @Param("operationKey") String operationKey,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
