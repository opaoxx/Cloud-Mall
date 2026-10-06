package com.cloudmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.user.domain.po.UserTccReceivePO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** HTTP 接收认领的唯一数据库访问入口。 */
@Mapper
public interface UserTccReceiveMapper extends BaseMapper<UserTccReceivePO> {
  /** 为新旧本地库幂等创建接收凭据。 */
  @Update(
      "CREATE TABLE IF NOT EXISTS user_tcc_receive (id BIGINT PRIMARY KEY,pay_no VARCHAR(64) NOT"
          + " NULL,xid VARCHAR(128) NOT NULL,branch_id BIGINT,user_id BIGINT NOT NULL,order_no"
          + " VARCHAR(64) NOT NULL,amount DECIMAL(18,2) NOT NULL,operation_key VARCHAR(128) NOT"
          + " NULL,ledger_key VARCHAR(128) NOT NULL,state VARCHAR(16) NOT NULL,created_at"
          + " DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,UNIQUE KEY"
          + " uk_user_tcc_receive_pay(pay_no),UNIQUE KEY uk_user_tcc_receive_xid(xid),UNIQUE KEY"
          + " uk_user_tcc_receive_operation(operation_key),UNIQUE KEY"
          + " uk_user_tcc_receive_ledger(ledger_key))")
  void ensureTable();

  /** 在独立事务中认领原请求，重复请求不获得分支登记权限。 */
  @Insert(
      "INSERT IGNORE INTO"
          + " user_tcc_receive(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at)"
          + " VALUES(#{id},#{payNo},#{xid},#{userId},#{orderNo},#{amount},#{operationKey},#{ledgerKey},'CLAIMED',#{createdAt},#{updatedAt})")
  int claim(UserTccReceivePO receipt);

  /** 当前读校验冲突记录，避免 INSERT IGNORE 后复用旧 MVCC 快照。 */
  @Select("SELECT * FROM user_tcc_receive WHERE pay_no=#{payNo} FOR UPDATE")
  UserTccReceivePO lockByPayNo(@Param("payNo") String payNo);

  /** 只读获取原接收载荷。 */
  @Select("SELECT * FROM user_tcc_receive WHERE operation_key=#{operationKey}")
  UserTccReceivePO findByOperationKey(@Param("operationKey") String operationKey);

  /** 仅首次 POST 可以补记分支；GET 永远不调用本方法。 */
  @Update(
      "UPDATE user_tcc_receive SET branch_id=#{branchId},updated_at=#{now} WHERE pay_no=#{payNo}"
          + " AND xid=#{xid} AND branch_id IS NULL")
  int bindBranch(
      @Param("payNo") String payNo,
      @Param("xid") String xid,
      @Param("branchId") Long branchId,
      @Param("now") java.time.LocalDateTime now);
}
