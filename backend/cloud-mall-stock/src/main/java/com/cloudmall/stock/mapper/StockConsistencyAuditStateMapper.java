package com.cloudmall.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.stock.domain.po.StockConsistencyAuditStatePO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 普通库存审计游标的幂等建表、读取和条件推进。 */
@Mapper
public interface StockConsistencyAuditStateMapper extends BaseMapper<StockConsistencyAuditStatePO> {
  /** 已有本地数据卷启动时创建审计状态表。 */
  @Update("CREATE TABLE IF NOT EXISTS stock_consistency_audit_state ("
      + "state_key VARCHAR(32) PRIMARY KEY,cursor_sku_id BIGINT NOT NULL,"
      + "cycle_started_at DATETIME(3) NOT NULL,last_completed_at DATETIME(3),"
      + "last_batch_count INT NOT NULL,updated_at DATETIME(3) NOT NULL,"
      + "last_error VARCHAR(512))")
  void ensureTable();

  /** 首次启动创建唯一普通库存审计游标。 */
  @Insert("INSERT IGNORE INTO stock_consistency_audit_state"
      + "(state_key,cursor_sku_id,cycle_started_at,last_batch_count,updated_at)"
      + " VALUES('NORMAL_STOCK',0,#{now},0,#{now})")
  int insertIfAbsent(@Param("now") LocalDateTime now);

  /** 读取本轮审计的持久游标。 */
  @Select("SELECT * FROM stock_consistency_audit_state WHERE state_key='NORMAL_STOCK'")
  StockConsistencyAuditStatePO findState();

  /** 只有观察完成并仍持有旧游标的实例才可以推进进度。 */
  @Update("UPDATE stock_consistency_audit_state SET cursor_sku_id=#{newCursor},"
      + "last_batch_count=#{batchCount},last_error=NULL,updated_at=#{now}"
      + " WHERE state_key='NORMAL_STOCK' AND cursor_sku_id=#{oldCursor}")
  int advance(@Param("oldCursor") long oldCursor, @Param("newCursor") long newCursor,
      @Param("batchCount") int batchCount, @Param("now") LocalDateTime now);

  /** 空批次标记一轮完整覆盖，并原子地从 SKU 0 开始新周期。 */
  @Update("UPDATE stock_consistency_audit_state SET cursor_sku_id=0,"
      + "cycle_started_at=#{now},last_completed_at=#{now},last_batch_count=0,"
      + "last_error=NULL,updated_at=#{now}"
      + " WHERE state_key='NORMAL_STOCK' AND cursor_sku_id=#{oldCursor}")
  int completeCycle(@Param("oldCursor") long oldCursor, @Param("now") LocalDateTime now);

  /** 记录有限错误摘要而不推进 SKU 游标。 */
  @Update("UPDATE stock_consistency_audit_state SET last_error=#{error},updated_at=#{now}"
      + " WHERE state_key='NORMAL_STOCK'")
  int recordFailure(@Param("error") String error, @Param("now") LocalDateTime now);
}
