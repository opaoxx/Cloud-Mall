package com.cloudmall.product.service;

import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.cloudmall.product.domain.po.ProductIndexRebuildJobPO;
import com.cloudmall.product.mapper.ProductIndexRebuildMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 以持久游标和有界 ES Bulk 渐进修复缺失的商品索引。 */
@Component
public class ProductIndexRebuildJob {
  /** 重建错误、代次变化和进度日志。 */
  private static final Logger log = LoggerFactory.getLogger(ProductIndexRebuildJob.class);
  /** 数据库 DATETIME(3) 使用的业务时区。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  /** 当前单一商品索引任务号。 */
  private static final String JOB_ID = "cloudmall-product-index";

  /** 重建游标与租约 Mapper。 */
  private final ProductIndexRebuildMapper rebuildMapper;
  /** 从商品事实库有界读取索引文档。 */
  private final ProductIndexDocumentLoader documentLoader;
  /** 执行 ES 版本化 Bulk 和索引核对。 */
  private final ProductIndexWriter indexWriter;
  /** 目标 ES 索引名。 */
  private final String targetIndex;
  /** 首次任务创建时的默认批量大小。 */
  private final int defaultBatchSize;
  /** 首次任务创建时每分钟最多索引的商品数。 */
  private final int defaultMaxEventsPerMinute;

  /** 创建可重启的商品索引重建任务。 */
  public ProductIndexRebuildJob(
      ProductIndexRebuildMapper rebuildMapper,
      ProductIndexDocumentLoader documentLoader,
      ProductIndexWriter indexWriter,
      Environment environment) {
    this.rebuildMapper = rebuildMapper;
    this.documentLoader = documentLoader;
    this.indexWriter = indexWriter;
    this.targetIndex =
        environment.getProperty("cloudmall.product.elasticsearch.index", "cloudmall_product");
    this.defaultBatchSize =
        Math.min(500, Math.max(1,
            environment.getProperty("cloudmall.product.index.rebuild-batch-size", Integer.class, 200)));
    this.defaultMaxEventsPerMinute =
        Math.max(1, environment.getProperty(
            "cloudmall.product.index.rebuild-max-events-per-minute", Integer.class, 6000));
  }

  /** 现有数据卷首次启动时建立任务表，之后保留原游标与暂停状态。 */
  @PostConstruct
  public void prepare() {
    // 1. 在 Mapper 内完成幂等建表。
    rebuildMapper.ensureTable();
    // 2. 只有任务不存在时写入默认限速配置，重启不覆盖运行进度。
    rebuildMapper.insertIfAbsent(
        JOB_ID, targetIndex, defaultBatchSize, defaultMaxEventsPerMinute, now());
  }

  /** 每轮最多处理一个有界批次，Bulk 成功前不得推进数据库游标。 */
  @Scheduled(
      fixedDelayString = "${cloudmall.product.index.rebuild-interval-ms:1000}",
      initialDelayString = "${cloudmall.product.index.rebuild-interval-ms:1000}")
  public void runNextBatch() {
    // 1. 读取任务状态与速率窗口；暂停、完成和退避中的任务不访问 ES。
    LocalDateTime currentTime = now();
    ProductIndexRebuildJobPO visible = rebuildMapper.findJob(JOB_ID);
    if (visible == null || "PAUSED".equals(visible.status) || "COMPLETED".equals(visible.status))
      return;
    if (!isRateWindowDue(visible, currentTime)) return;

    // 2. 认领数据库租约，同一时刻只有一个实例处理该游标。
    String owner = UUID.randomUUID().toString();
    if (rebuildMapper.claim(
            JOB_ID, owner, currentTime.plusSeconds(30), currentTime, currentTime.minusSeconds(30))
        != 1) return;
    ProductIndexRebuildJobPO claimed = rebuildMapper.findJob(JOB_ID);
    if (claimed == null || !owner.equals(claimed.leaseOwner))
      throw new IllegalStateException("商品索引重建租约认领后不可见");

    try {
      if (!targetIndex.equals(claimed.targetIndex))
        throw new IllegalStateException("重建任务目标索引与服务配置不一致");
      int batchSize = claimed.batchSize == null ? 0 : claimed.batchSize;
      if (batchSize < 1 || batchSize > 500)
        throw new IllegalStateException("重建批次必须在 1 至 500 之间");
      long oldCursor = claimed.cursorProductId == null ? 0L : claimed.cursorProductId;
      // 3. 处理中若索引被删除，重新开始一个代次，避免跳过先前批次。
      if (oldCursor > 0 && !indexWriter.indexExists()) {
        if (rebuildMapper.restartClaimedAfterIndexLoss(JOB_ID, owner, now()) != 1)
          throw new IllegalStateException("目标索引丢失后重建游标无法重置");
        log.warn("商品 ES 索引丢失，重置游标并开始新代次，index={}", targetIndex);
        return;
      }
      indexWriter.ensureIndex();
      List<ProductIndexDocument> documents = documentLoader.loadBatch(oldCursor, batchSize);
      if (!documents.isEmpty()) indexWriter.upsertBatch(documents);
      // 4. 完整成功后才更新进度；重复 Bulk 由 external version 保证幂等。
      long newCursor = documents.isEmpty()
          ? oldCursor : documents.get(documents.size() - 1).id();
      String nextStatus = documents.size() < batchSize ? "COMPLETED" : "RUNNING";
      if (rebuildMapper.finishBatch(
              JOB_ID, owner, oldCursor, newCursor, documents.size(), nextStatus, now())
          != 1) throw new IllegalStateException("商品索引重建游标更新失败");
      if ("COMPLETED".equals(nextStatus))
        log.info("商品 ES 索引存量重建完成，index={}，cursor={}", targetIndex, newCursor);
    } catch (Exception rebuildFailure) {
      // 5. ES 或数据库故障保留原游标，30 秒退避后继续原批次。
      rebuildMapper.markFailed(JOB_ID, owner, compactError(rebuildFailure), now());
      log.warn("商品 ES 索引批量重建失败，保留原游标等待恢复，index={}", targetIndex,
          rebuildFailure);
    }
  }

  /** 周期核对已完成索引，目标索引丢失或文档不足时开启新代次。 */
  @Scheduled(
      fixedDelayString = "${cloudmall.product.index.rebuild-audit-ms:300000}",
      initialDelayString = "${cloudmall.product.index.rebuild-audit-ms:300000}")
  public void auditCompletedIndex() {
    ProductIndexRebuildJobPO job = rebuildMapper.findJob(JOB_ID);
    if (job == null || !"COMPLETED".equals(job.status)) return;
    try {
      boolean indexPresent = indexWriter.indexExists();
      long sourceCount = documentLoader.countSources();
      long indexCount = indexPresent ? indexWriter.countDocuments() : 0L;
      if (!indexPresent || indexCount < sourceCount) {
        if (rebuildMapper.restartCompleted(JOB_ID, now()) == 1)
          log.warn("商品 ES 索引缺失或数量不足，重新限速重建，mysql={}，es={}",
              sourceCount, indexCount);
      } else if (indexCount > sourceCount) {
        log.warn("商品 ES 文档数大于 MySQL 商品数，需核对历史删除投影，mysql={}，es={}",
            sourceCount, indexCount);
      }
    } catch (Exception auditFailure) {
      log.warn("商品 ES 索引数量核对失败，下一轮继续", auditFailure);
    }
  }

  /** 按当前批量大小与每分钟额度计算两次 Bulk 的最短间隔。 */
  private static boolean isRateWindowDue(
      ProductIndexRebuildJobPO job, LocalDateTime currentTime) {
    if (job.lastBulkAt == null) return true;
    int batchSize = job.batchSize == null ? 0 : job.batchSize;
    int maxPerMinute = job.maxEventsPerMinute == null ? 0 : job.maxEventsPerMinute;
    if (batchSize < 1 || maxPerMinute < 1) return true;
    long minimumMillis = (long) Math.ceil(batchSize * 60000.0 / maxPerMinute);
    return Duration.between(job.lastBulkAt, currentTime).toMillis() >= minimumMillis;
  }

  /** 把失败摘要限制在数据库列宽内，保留完整异常于服务日志。 */
  private static String compactError(Exception failure) {
    String message = failure.toString();
    return message.substring(0, Math.min(1024, message.length()));
  }

  /** 生成 Asia/Shanghai 语义的 MySQL 本地时间。 */
  private static LocalDateTime now() {
    return LocalDateTime.now(BUSINESS_ZONE);
  }
}
