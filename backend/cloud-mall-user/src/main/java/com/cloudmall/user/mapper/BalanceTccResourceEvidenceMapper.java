package com.cloudmall.user.mapper;

import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 查询 TCC 资源回调的既有业务归属证明，不负责修改资金状态。 */
@Mapper
public interface BalanceTccResourceEvidenceMapper {
  /** 统计指定 XID/分支的共享扣款归属。 */
  @Select("SELECT COUNT(*) FROM balance_debit_attempt WHERE xid=#{xid} AND branch_id=#{branchId}")
  int countIncomingAttempts(@Param("xid") String xid, @Param("branchId") Long branchId);

  /** 统计指定 XID/分支的 TCC 冻结事实。 */
  @Select("SELECT COUNT(*) FROM balance_tcc_debit WHERE xid=#{xid} AND branch_id=#{branchId}")
  int countIncomingTccDebits(@Param("xid") String xid, @Param("branchId") Long branchId);

  /** 查询与回调任一唯一业务键匹配的既有归属，状态与模式由调用方解释。 */
  @Select(
      "SELECT * FROM balance_debit_attempt WHERE xid=#{xid} OR"
          + " pay_no=#{payNo} OR ledger_key=#{ledgerKey} OR operation_key=#{operationKey}")
  List<BalanceDebitAttemptPO> findMatchingAttempts(
      @Param("xid") String xid,
      @Param("branchId") Long branchId,
      @Param("payNo") String payNo,
      @Param("ledgerKey") String ledgerKey,
      @Param("operationKey") String operationKey);
}
