package com.cloudmall.user.mapper;

import com.cloudmall.user.domain.po.TccFenceLogPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Seata 官方 TCC Fence 表访问接口，使用显式 SQL 处理复合主键。 */
@Mapper
public interface TccFenceLogMapper {
  /** 幂等创建 Seata 1.5.2 官方 Fence 表。 */
  @Update(
      "CREATE TABLE IF NOT EXISTS tcc_fence_log (xid VARCHAR(128) NOT NULL,branch_id BIGINT NOT"
          + " NULL,action_name VARCHAR(64) NOT NULL,status TINYINT NOT NULL,gmt_create DATETIME(3)"
          + " NOT NULL,gmt_modified DATETIME(3) NOT NULL,PRIMARY KEY (xid,branch_id),KEY"
          + " idx_gmt_modified(gmt_modified),KEY idx_status(status)) ENGINE=InnoDB DEFAULT"
          + " CHARSET=utf8mb4")
  void ensureTable();

  /** 官方 Fence 用原生 JDBC 写入，不会清除 MyBatis 缓存；每次必须读取真实分支状态。 */
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  @Select(
      "SELECT xid,branch_id,action_name,status,gmt_create,gmt_modified FROM tcc_fence_log WHERE"
          + " xid=#{xid} AND branch_id=#{branchId}")
  TccFenceLogPO findByBranch(@Param("xid") String xid, @Param("branchId") Long branchId);
}
