package com.cloudmall.product.service.impl.metrics;

import com.cloudmall.product.mapper.ProductHotStatMapper;
import com.cloudmall.product.mapper.ProductIndexMapper;
import com.cloudmall.product.util.ProductDateTimes;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.index.query.QueryBuilders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只读核对商品热度来源和新鲜度，绝不自动修改指标或 ES。 */
@Component
@SuppressWarnings("deprecation")
public class ProductHotStatFreshnessAudit {
  /** 业务 DATETIME(3) 的固定时区。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** 来源缺失、索引不完整和历史快照过期的审计日志。 */
  private static final Logger log = LoggerFactory.getLogger(ProductHotStatFreshnessAudit.class);

  /** 热度来源汇总 Mapper。 */
  private final ProductHotStatMapper hotStatMapper;

  /** 商品事实总数 Mapper。 */
  private final ProductIndexMapper productIndexMapper;

  /** 商品 ES 索引只读计数客户端。 */
  private final RestHighLevelClient elasticsearch;

  /** 本实例 ES 物理索引名。 */
  private final String indexName;

  /** 热度来源未知行数指标。 */
  private final AtomicLong unknownRows = new AtomicLong();

  /** ES 缺失来源标记文档数指标。 */
  private final AtomicLong missingIndexSources = new AtomicLong();

  /** ES 与 MySQL 历史来源标记不一致的文档数。 */
  private final AtomicLong inconsistentLegacySources = new AtomicLong();

  /** 最近一次审计是否成功完成，1 为成功，0 为失败或尚未执行。 */
  private final AtomicLong auditHealthy = new AtomicLong();

  /** 最近指标更新时间距今秒数。 */
  private final AtomicLong latestAgeSeconds = new AtomicLong();

  /** 避免对同一持续过期状态每五分钟重复打印告警。 */
  private final AtomicBoolean previouslyStale = new AtomicBoolean();

  /** 创建只读审计和固定维度 Prometheus Gauge。 */
  public ProductHotStatFreshnessAudit(
      ProductHotStatMapper hotStatMapper,
      ProductIndexMapper productIndexMapper,
      RestHighLevelClient elasticsearch,
      MeterRegistry meterRegistry,
      @Value("${cloudmall.product.elasticsearch.index:cloudmall_product}") String indexName) {
    this.hotStatMapper = hotStatMapper;
    this.productIndexMapper = productIndexMapper;
    this.elasticsearch = elasticsearch;
    this.indexName = indexName;
    Gauge.builder("cloudmall.product.hot_stat.unknown_rows", unknownRows, AtomicLong::get)
        .register(meterRegistry);
    Gauge.builder(
            "cloudmall.product.hot_stat.index_missing_sources",
            missingIndexSources,
            AtomicLong::get)
        .register(meterRegistry);
    Gauge.builder(
            "cloudmall.product.hot_stat.inconsistent_legacy_sources",
            inconsistentLegacySources,
            AtomicLong::get)
        .register(meterRegistry);
    Gauge.builder("cloudmall.product.hot_stat.audit_healthy", auditHealthy, AtomicLong::get)
        .register(meterRegistry);
    Gauge.builder(
            "cloudmall.product.hot_stat.latest_age_seconds", latestAgeSeconds, AtomicLong::get)
        .register(meterRegistry);
  }

  /** 每轮仅读取 MySQL/ES 来源计数，超过阈值时告警而不修改事实。 */
  @Scheduled(
      fixedDelayString = "${cloudmall.product.hot-stat.audit-interval-ms:300000}",
      initialDelayString = "${cloudmall.product.hot-stat.audit-initial-delay-ms:10000}")
  public void audit() {
    // 1. 对比当前 MySQL 商品数和来源汇总，未知来源保留审计证据。
    try {
      Map<String, Object> summary = hotStatMapper.summarizeSources();
      long total = ((Number) summary.get("total_count")).longValue();
      long legacy = ((Number) summary.get("legacy_count")).longValue();
      long unknown = ((Number) summary.get("unknown_count")).longValue();
      long products = productIndexMapper.countIndexSources();
      unknownRows.set(unknown);
      // 2. 固定上海时区解释 DATETIME(3)，仅计算观测到的最近更新时间。
      Object updatedValue = summary.get("latest_legacy_update");
      long ageSeconds =
          legacy == 0L
              ? 0L
              : updatedValue == null
                  ? Long.MAX_VALUE
                  : Math.max(
                      0L,
                      Duration.between(
                              ProductDateTimes.asLocalDateTime(updatedValue)
                                  .atZone(BUSINESS_ZONE)
                                  .toInstant(),
                              java.time.Instant.now())
                          .getSeconds());
      latestAgeSeconds.set(ageSeconds);
      long warningHours = Math.max(1L, freshnessWarningHours);
      if (ageSeconds > Duration.ofHours(warningHours).getSeconds()) {
        if (previouslyStale.compareAndSet(false, true))
          log.warn(
              "商品热度指标超过新鲜度阈值，ageHours={}，legacyRows={}，unknownRows={}",
              ageSeconds / 3600L,
              legacy,
              unknown);
      } else {
        previouslyStale.set(false);
      }
      // 3. ES 来源字段缺失仅告警，不能把审计变成索引修复任务。
      long indexed =
          elasticsearch.count(new CountRequest(indexName), RequestOptions.DEFAULT).getCount();
      long marked =
          elasticsearch
              .count(
                  new CountRequest(indexName).query(QueryBuilders.existsQuery("metricSourceType")),
                  RequestOptions.DEFAULT)
              .getCount();
      long missing = Math.max(0L, indexed - marked);
      missingIndexSources.set(missing);
      long matchingLegacy =
          elasticsearch
              .count(
                  new CountRequest(indexName)
                      .query(
                          QueryBuilders.boolQuery()
                              .filter(QueryBuilders.termQuery("metricSourceType", "LEGACY_IMPORT"))
                              .filter(QueryBuilders.termQuery("metricSourceRef", "hm-item.item"))),
                  RequestOptions.DEFAULT)
              .getCount();
      long inconsistentLegacy = Math.max(0L, legacy - matchingLegacy);
      inconsistentLegacySources.set(inconsistentLegacy);
      auditHealthy.set(1L);
      if (unknown > 0
          || missing > 0
          || inconsistentLegacy > 0
          || indexed != products
          || total > products)
        log.warn(
            "商品热度来源审计发现差异，mysqlProducts={}，hotRows={}，legacyRows={}，"
                + "unknownRows={}，esDocs={}，esMissingSource={}，inconsistentLegacy={}",
            products,
            total,
            legacy,
            unknown,
            indexed,
            missing,
            inconsistentLegacy);
    } catch (Exception auditFailure) {
      auditHealthy.set(0L);
      log.warn("商品热度来源只读审计失败，下轮重试，index={}", indexName, auditFailure);
    }
  }

  /** 当前历史快照过期告警阈值，缺省 24 小时。 */
  @Value("${cloudmall.product.hot-stat.freshness-warning-hours:24}")
  private long freshnessWarningHours = 24L;
}
