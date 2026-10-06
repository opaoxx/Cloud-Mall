package com.cloudmall.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Pay TCC 派发事实数据访问接口。 */
@Mapper
public interface PayTccDispatchMapper extends BaseMapper<PayTccDispatchPO> {
  /** 幂等创建派发表。 */
  @Update(
      "CREATE TABLE IF NOT EXISTS pay_tcc_dispatch (id BIGINT PRIMARY KEY,pay_no VARCHAR(64) NOT"
          + " NULL,xid VARCHAR(128) NOT NULL,user_id BIGINT NOT NULL,order_no VARCHAR(64) NOT"
          + " NULL,amount DECIMAL(18,2) NOT NULL,operation_key VARCHAR(128) NOT NULL,ledger_key"
          + " VARCHAR(128) NOT NULL,state VARCHAR(32) NOT NULL,post_attempted_at"
          + " DATETIME(3),user_branch_id BIGINT,last_error VARCHAR(512),created_at DATETIME(3) NOT"
          + " NULL,updated_at DATETIME(3) NOT NULL,UNIQUE KEY"
          + " uk_pay_tcc_dispatch_pay(pay_no),UNIQUE KEY uk_pay_tcc_dispatch_xid(xid),UNIQUE KEY"
          + " uk_pay_tcc_dispatch_operation(operation_key),UNIQUE KEY"
          + " uk_pay_tcc_dispatch_ledger(ledger_key))")
  void ensureTable();

  /** 按支付号锁定派发事实。 */
  @Select("select * from pay_tcc_dispatch where pay_no=#{payNo} for update")
  PayTccDispatchPO selectByPayNoForUpdate(@Param("payNo") String payNo);

  /** 按支付号读取派发事实。 */
  @Select("select * from pay_tcc_dispatch where pay_no=#{payNo}")
  PayTccDispatchPO selectByPayNo(@Param("payNo") String payNo);

  /** 在镜像事务内锁定派发状态。 */
  @Select("select * from pay_tcc_dispatch where pay_no=#{payNo} for update")
  PayTccDispatchPO selectForUpdate(@Param("payNo") String payNo);

  /** 将 PREPARED 唯一转换为 POST_ATTEMPTED。 */
  @Update(
      "update pay_tcc_dispatch set"
          + " state='POST_ATTEMPTED',post_attempted_at=#{attemptedAt},updated_at=#{updatedAt} where"
          + " pay_no=#{payNo} and state='PREPARED'")
  int markPostAttempted(
      @Param("payNo") String payNo,
      @Param("attemptedAt") LocalDateTime attemptedAt,
      @Param("updatedAt") LocalDateTime updatedAt);

  /** 以当前镜像状态为条件更新只读 User 结果。 */
  @Update(
      "update pay_tcc_dispatch set"
          + " state=#{targetState},user_branch_id=coalesce(user_branch_id,#{branchId}),last_error=#{lastError},updated_at=#{updatedAt}"
          + " where pay_no=#{payNo} and state=#{expectedState} and state not in"
          + " ('CONFIRMED','CANCELLED')")
  int mirrorState(
      @Param("payNo") String payNo,
      @Param("expectedState") String expectedState,
      @Param("targetState") String targetState,
      @Param("branchId") Long branchId,
      @Param("lastError") String lastError,
      @Param("updatedAt") LocalDateTime updatedAt);

  /** 按 XID 查询派发事实，供冲突诊断使用。 */
  @Select("select * from pay_tcc_dispatch where xid=#{xid}")
  List<PayTccDispatchPO> selectByXid(@Param("xid") String xid);
}
