package com.cloudmall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.product.domain.po.ProductIndexRebuildJobPO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 商品 ES 重建任务的建表、游标和多实例租约持久层。 */
@Mapper
public interface ProductIndexRebuildMapper extends BaseMapper<ProductIndexRebuildJobPO> {
  /** 为现有本地 MySQL 数据卷准备持久任务表。 */
  @Update(
      "CREATE TABLE IF NOT EXISTS product_index_rebuild_job ("
          + "job_id VARCHAR(64) PRIMARY KEY,target_index VARCHAR(128) NOT NULL,"
          + "index_generation INT NOT NULL,cursor_product_id BIGINT NOT NULL,"
          + "batch_size INT NOT NULL,max_events_per_minute INT NOT NULL,"
          + "last_batch_count INT NOT NULL,last_bulk_at DATETIME(3),"
          + "status VARCHAR(32) NOT NULL,lease_owner VARCHAR(64),lease_until DATETIME(3),"
          + "last_error VARCHAR(1024),created_at DATETIME(3) NOT NULL,"
          + "updated_at DATETIME(3) NOT NULL,"
          + "KEY idx_product_rebuild_status(status,lease_until))")
  void ensureTable();

  /** 第一次部署时幂等插入任务，重启后沿用原游标。 */
  @Insert(
      "INSERT IGNORE INTO product_index_rebuild_job("
          + "job_id,target_index,index_generation,cursor_product_id,batch_size,"
          + "max_events_per_minute,last_batch_count,status,created_at,updated_at)"
          + " VALUES(#{jobId},#{targetIndex},1,0,#{batchSize},#{maxEventsPerMinute},0,"
          + "'PENDING',#{now},#{now})")
  int insertIfAbsent(
      @Param("jobId") String jobId,
      @Param("targetIndex") String targetIndex,
      @Param("batchSize") int batchSize,
      @Param("maxEventsPerMinute") int maxEventsPerMinute,
      @Param("now") LocalDateTime now);

  /** 读取当前重建游标与状态。 */
  @Select("SELECT * FROM product_index_rebuild_job WHERE job_id=#{jobId}")
  ProductIndexRebuildJobPO findJob(@Param("jobId") String jobId);

  /** 仅到期且未暂停/完成的任务可认领；失败任务遵守重试宽限。 */
  @Update(
      "UPDATE product_index_rebuild_job SET lease_owner=#{owner},lease_until=#{leaseUntil},"
          + "status='RUNNING',updated_at=#{now} WHERE job_id=#{jobId}"
          + " AND status IN ('PENDING','RUNNING','FAILED')"
          + " AND (status<>'FAILED' OR updated_at<=#{retryBefore})"
          + " AND (lease_until IS NULL OR lease_until<#{now})")
  int claim(
      @Param("jobId") String jobId,
      @Param("owner") String owner,
      @Param("leaseUntil") LocalDateTime leaseUntil,
      @Param("now") LocalDateTime now,
      @Param("retryBefore") LocalDateTime retryBefore);

  /** Bulk 完全成功后才推进原游标，并释放当前实例租约。 */
  @Update(
      "UPDATE product_index_rebuild_job SET cursor_product_id=#{newCursor},"
          + "last_batch_count=#{batchCount},last_bulk_at=#{now},status=#{status},"
          + "last_error=NULL,lease_owner=NULL,lease_until=NULL,updated_at=#{now}"
          + " WHERE job_id=#{jobId} AND lease_owner=#{owner}"
          + " AND cursor_product_id=#{oldCursor}")
  int finishBatch(
      @Param("jobId") String jobId,
      @Param("owner") String owner,
      @Param("oldCursor") long oldCursor,
      @Param("newCursor") long newCursor,
      @Param("batchCount") int batchCount,
      @Param("status") String status,
      @Param("now") LocalDateTime now);

  /** ES 或 MySQL 暂时不可用时保留原游标并退避自动恢复。 */
  @Update(
      "UPDATE product_index_rebuild_job SET status='FAILED',last_error=#{error},"
          + "last_bulk_at=#{now},lease_owner=NULL,lease_until=NULL,updated_at=#{now}"
          + " WHERE job_id=#{jobId} AND lease_owner=#{owner}")
  int markFailed(
      @Param("jobId") String jobId,
      @Param("owner") String owner,
      @Param("error") String error,
      @Param("now") LocalDateTime now);

  /** 处理中发现目标 ES 索引已丢失时开始下一代完整重建。 */
  @Update(
      "UPDATE product_index_rebuild_job SET index_generation=index_generation+1,"
          + "cursor_product_id=0,last_batch_count=0,status='PENDING',"
          + "last_error=NULL,lease_owner=NULL,lease_until=NULL,updated_at=#{now}"
          + " WHERE job_id=#{jobId} AND lease_owner=#{owner}")
  int restartClaimedAfterIndexLoss(
      @Param("jobId") String jobId,
      @Param("owner") String owner,
      @Param("now") LocalDateTime now);

  /** 已完成任务若发现目标索引丢失或文档不足则重新启动。 */
  @Update(
      "UPDATE product_index_rebuild_job SET index_generation=index_generation+1,"
          + "cursor_product_id=0,last_batch_count=0,status='PENDING',"
          + "last_error=NULL,lease_owner=NULL,lease_until=NULL,updated_at=#{now}"
          + " WHERE job_id=#{jobId} AND status='COMPLETED'"
          + " AND (lease_until IS NULL OR lease_until<#{now})")
  int restartCompleted(
      @Param("jobId") String jobId, @Param("now") LocalDateTime now);
}
