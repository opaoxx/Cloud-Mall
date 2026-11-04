package com.cloudmall.product.service.impl;

import com.cloudmall.common.api.PageResult;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.product.domain.dto.ProductReadResultDTO;
import com.cloudmall.product.domain.po.ProductSearchHit;
import com.cloudmall.product.domain.po.ProductSearchPage;
import com.cloudmall.product.domain.vo.HotStatVO;
import com.cloudmall.product.domain.vo.ProductVO;
import com.cloudmall.product.domain.vo.SkuSummaryVO;
import com.cloudmall.product.mapper.ProductSqlMapper;
import com.cloudmall.product.service.ProductQueryService;
import com.cloudmall.product.service.impl.metrics.ProductMetricService;
import com.cloudmall.product.service.impl.search.ElasticsearchProductKeywordSearch;
import com.cloudmall.product.util.ProductDateTimes;
import com.cloudmall.product.util.ProductValueCodec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 协调商品查询、SQL/ES事实快照以及在线指标登记。 */
@Service
public class ProductQueryServiceImpl implements ProductQueryService {

  /** 当前模式是否登记在线指标。 */
  @Value("${cloudmall.product.metrics.enabled:false}")
  private boolean metricsEnabled;

  /** 显式LEGACY或ONLINE排序模式。 */
  @Value("${cloudmall.product.metrics.sort-mode:LEGACY}")
  private String metricSortMode = "LEGACY";

  /** 仅在启用模式下必需的原指标事务代理。 */
  @Autowired(required = false)
  private ProductMetricService metrics;

  /** 慢读与事实变更的诊断日志。 */
  private static final Logger log = LoggerFactory.getLogger(ProductQueryServiceImpl.class);

  /** 原列表查询允许的最大数据库偏移。 */
  private static final long MAX_LIST_OFFSET = 100_000L;

  /** 商品事实查询Mapper。 */
  private final ProductSqlMapper productSqlMapper;

  /** 原商品与附属数据装配器。 */
  private final ProductCatalogReader catalogReader;

  /** 原金额与SKU JSON编解码器。 */
  private final ProductValueCodec valueCodec;

  /** 关键词是否使用ES路径。 */
  @Value("${cloudmall.product.search.enabled:false}")
  private boolean searchEnabled;

  /** ES物理索引名。 */
  @Value("${cloudmall.product.elasticsearch.index:cloudmall_product}")
  private String searchIndexName = "cloudmall_product";

  /** 可选的远端关键词查询入口。 */
  @Autowired(required = false)
  private ElasticsearchProductKeywordSearch productKeywordSearch;

  /** 可选的短事务管理器，ONLINE模式在启动时校验必需。 */
  @Autowired(required = false)
  private PlatformTransactionManager transactionManager;

  /** 注入事实读取与字段编解码依赖，可选模式依赖沿原装配规则注入。 */
  public ProductQueryServiceImpl(
      ProductSqlMapper productSqlMapper,
      ProductCatalogReader catalogReader,
      ProductValueCodec valueCodec) {
    this.productSqlMapper = productSqlMapper;
    this.catalogReader = catalogReader;
    this.valueCodec = valueCodec;
  }

  /** 归一化分页，按原路径查询并将搜索登记关联号交给HTTP层。 */
  @Override
  public ProductReadResultDTO<PageResult<ProductVO>> products(
      String keyword, Long categoryId, Integer status, int page, int pageSize, String sort) {
    // 1. 生成原登记关联号并归一化分页，深分页在任何列表SQL之前拒绝。
    long startedAt = System.nanoTime();
    String metricRequestId = UUID.randomUUID().toString();
    page = Math.max(1, page);
    pageSize = Math.min(Math.max(1, pageSize), 100);
    long offset = Math.multiplyExact((long) page - 1L, pageSize);
    if (offset > MAX_LIST_OFFSET) throw new BizException(ErrorCodes.INVALID, "商品列表页码超出可查询范围", 400);
    final int normalizedPage = page;
    final int normalizedPageSize = pageSize;
    // 2. 按原SQL/ES和ONLINE模式取得完整事实页。
    PageResult<ProductVO> result =
        searchEnabled && keyword != null && !keyword.isBlank()
            ? searchProducts(
                keyword.trim(),
                categoryId,
                status,
                normalizedPage,
                normalizedPageSize,
                (int) offset,
                sort)
            : withReadTransaction(
                () ->
                    sqlProducts(
                        keyword,
                        categoryId,
                        status,
                        normalizedPage,
                        normalizedPageSize,
                        (int) offset,
                        sort));
    // 3. 登记本次搜索并保留原指标页元数据、慢读日志和关联号。
    if (metricsEnabled && keyword != null && !keyword.isBlank()) {
      try {
        metrics.registerSearchPage(
            metricRequestId,
            result.items.stream().map(item -> item.id).toList(),
            MDC.get("traceId"));
      } catch (org.springframework.dao.DataAccessException
          | org.springframework.transaction.TransactionException failure) {
        throw metricUnavailable(failure);
      }
    }
    if ("ONLINE".equals(metricSortMode)
        && !(result instanceof com.cloudmall.product.domain.vo.ProductMetricPageVO))
      result = new com.cloudmall.product.domain.vo.ProductMetricPageVO<>(result, null);
    long elapsedMillis =
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    if (elapsedMillis > 200L)
      log.warn(
          "商品列表慢读，elapsedMs={}，page={}，pageSize={}，sort={}，keyword={}，categoryFiltered={}，statusFiltered={}，total={}，rows={}，traceId={}",
          elapsedMillis,
          page,
          pageSize,
          sort,
          keyword != null && !keyword.isBlank(),
          categoryId != null,
          status != null,
          result.total,
          result.items.size(),
          MDC.get("traceId"));
    return new ProductReadResultDTO<>(result, metricsEnabled ? metricRequestId : null);
  }

  /** 保留原MySQL分页和在线排序快照语义。 */
  private PageResult<ProductVO> sqlProducts(
      String keyword,
      Long categoryId,
      Integer status,
      int page,
      int pageSize,
      int offset,
      String sort) {
    long total = productSqlMapper.countProducts(keyword, categoryId, status);
    if (offset >= total) {
      PageResult<ProductVO> empty = new PageResult<>(List.of(), page, pageSize, total);
      return onlineRank(sort)
          ? new com.cloudmall.product.domain.vo.ProductMetricPageVO<>(empty, metrics.sqlRevision())
          : empty;
    }
    List<ProductVO> items =
        (onlineRank(sort)
                ? productSqlMapper.findProductsOnline(
                    keyword, categoryId, status, offset, pageSize, sort)
                : productSqlMapper.findProducts(
                    keyword, categoryId, status, offset, pageSize, sort))
            .stream().map(catalogReader::productView).toList();
    PageResult<ProductVO> result = new PageResult<>(items, page, pageSize, total);
    return onlineRank(sort)
        ? new com.cloudmall.product.domain.vo.ProductMetricPageVO<>(result, metrics.sqlRevision())
        : result;
  }

  /** 在数据库事务外检索ES，再用短事务验证完整事实页及在线指标窗口。 */
  private PageResult<ProductVO> searchProducts(
      String keyword,
      Long categoryId,
      Integer status,
      int page,
      int pageSize,
      int offset,
      String sort) {
    if (productKeywordSearch == null)
      throw new BizException("PRODUCT_SEARCH_UNAVAILABLE", "商品检索暂时不可用", 503);
    long metricDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    boolean online = onlineRank(sort);
    for (int attempt = 0; attempt < 2; attempt++) {
      com.cloudmall.product.service.impl.metrics.ProductMetricService.Cut cut = null;
      if (online) {
        if (System.nanoTime() >= metricDeadline) break;
        try {
          cut = metrics.cut();
        } catch (org.springframework.dao.DataAccessException
            | org.springframework.transaction.TransactionException unavailable) {
          continue;
        }
        if (!cut.admissible()) continue;
        try {
          if (!cut.indexUuid().equals(productKeywordSearch.indexUuid(metricDeadline))) continue;
        } catch (Exception unavailable) {
          continue;
        }
      }
      ProductSearchPage matches;
      try {
        matches =
            online
                ? productKeywordSearch.search(
                    keyword, categoryId, status, offset, pageSize, sort, metricDeadline)
                : productKeywordSearch.search(keyword, categoryId, status, offset, pageSize, sort);
      } catch (Exception searchFailure) {
        if (online) continue;
        log.warn(
            "商品 ES 检索失败，index={}，traceId={}，reason={}",
            searchIndexName,
            MDC.get("traceId"),
            searchFailure.toString());
        throw new BizException("PRODUCT_SEARCH_UNAVAILABLE", "商品检索暂时不可用", 503);
      }
      if (!online && matches.hits().isEmpty())
        return new PageResult<>(List.of(), page, pageSize, matches.total());
      if (!online) {
        PageResult<ProductVO> verified =
            withReadTransaction(
                () -> verifiedSearchPage(matches, categoryId, status, page, pageSize));
        if (verified != null) return verified;
        continue;
      }
      String actualUuid;
      try {
        actualUuid = productKeywordSearch.indexUuid(metricDeadline);
      } catch (Exception unavailable) {
        continue;
      }
      if (System.nanoTime() >= metricDeadline || !cut.indexUuid().equals(actualUuid)) continue;
      final com.cloudmall.product.service.impl.metrics.ProductMetricService.Cut acceptedCut = cut;
      PageResult<ProductVO> admitted;
      try {
        admitted =
            withOnlineReadTransaction(
                () -> {
                  PageResult<ProductVO> verified =
                      verifiedSearchPage(matches, categoryId, status, page, pageSize);
                  if (verified == null || !metrics.admit(acceptedCut, matches.hits())) return null;
                  return new com.cloudmall.product.domain.vo.ProductMetricPageVO<>(
                      verified, acceptedCut.revision());
                });
      } catch (org.springframework.dao.DataAccessException
          | org.springframework.transaction.TransactionException unavailable) {
        continue;
      }
      if (admitted != null) return admitted;
    }
    log.warn("商品 ES 页内事实连续两次变化，traceId={}，page={}，pageSize={}", MDC.get("traceId"), page, pageSize);
    throw new BizException("PRODUCT_SEARCH_RETRYABLE", "商品检索结果正在更新，请重试", 503);
  }

  /** 按ES原顺序验证全部命中，任一事实变化即丢弃整页。 */
  private PageResult<ProductVO> verifiedSearchPage(
      ProductSearchPage matches, Long categoryId, Integer status, int page, int pageSize) {
    List<Long> productIds = matches.hits().stream().map(ProductSearchHit::id).toList();
    Map<Long, Map<String, Object>> factsById = new HashMap<>();
    for (Map<String, Object> row : productSqlMapper.findProductFacts(productIds))
      factsById.put(((Number) row.get("id")).longValue(), row);
    List<ProductVO> items = new ArrayList<>();
    for (ProductSearchHit hit : matches.hits()) {
      Map<String, Object> fact = factsById.get(hit.id());
      if (fact == null
          || ((Number) fact.get("version")).longValue() != hit.productVersion()
          || ((Number) fact.get("status")).intValue() != hit.status()
          || !Objects.equals(fact.get("name"), hit.name())
          || ((Number) fact.get("category_id")).longValue() != hit.categoryId()
          || ((BigDecimal) fact.get("price")).compareTo(hit.price()) != 0
          || ProductDateTimes.asLocalDateTime(fact.get("updated_at"))
                  .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                  .toInstant()
                  .toEpochMilli()
              != hit.updatedAt()
          || status != null && ((Number) fact.get("status")).intValue() != status
          || categoryId != null && ((Number) fact.get("category_id")).longValue() != categoryId) {
        log.warn(
            "商品 ES 页内事实变化，index={}，productId={}，indexedVersion={}，factVersion={}，"
                + "discarded=1，traceId={}",
            searchIndexName,
            hit.id(),
            hit.productVersion(),
            fact == null ? null : fact.get("version"),
            MDC.get("traceId"));
        return null;
      }
      items.add(catalogReader.productView(fact));
    }
    return new PageResult<>(items, page, pageSize, matches.total());
  }

  /** 在可选的只读REPEATABLE_READ短事务内组装事实页。 */
  private <T> T withReadTransaction(java.util.function.Supplier<T> work) {
    if (transactionManager == null) return work.get();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setReadOnly(true);
    transaction.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    return transaction.execute(status -> work.get());
  }

  /** 以原三秒超时执行ONLINE事实读取与指标准入。 */
  private <T> T withOnlineReadTransaction(java.util.function.Supplier<T> work) {
    if (transactionManager == null)
      throw new IllegalStateException("METRIC_TRANSACTION_MANAGER_REQUIRED");
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setReadOnly(true);
    transaction.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    transaction.setTimeout(3);
    return transaction.execute(status -> work.get());
  }

  /** 读取商品事实后登记浏览事件并返回原关联号。 */
  @Override
  public ProductReadResultDTO<ProductVO> product(Long productId) {
    // 1. 读取原商品和附属数据，缺失事实先返回原错误。
    String requestId = UUID.randomUUID().toString();
    ProductVO result = catalogReader.requireProduct(productId);
    // 2. 启用时以同一关联号登记浏览，依赖异常沿原503传播。
    if (metricsEnabled)
      try {
        metrics.registerViews(requestId, productId, MDC.get("traceId"));
      } catch (org.springframework.dao.DataAccessException
          | org.springframework.transaction.TransactionException failure) {
        throw metricUnavailable(failure);
      }
    // 3. 由HTTP层使用该关联号装配统一响应。
    return new ProductReadResultDTO<>(result, metricsEnabled ? requestId : null);
  }

  /** 提供原可购买SKU事实和规格，缺失与下架保留原错误。 */
  @Override
  public SkuSummaryVO sku(Long skuId) {
    // 1. 读取SKU及商品状态，保留原缺失错误。
    List<Map<String, Object>> rows = productSqlMapper.findSku(skuId);
    if (rows.isEmpty()) throw new BizException("PRODUCT_SKU_NOT_FOUND", "SKU不存在", 404);
    Map<String, Object> row = rows.get(0);
    // 2. 同时核对商品和SKU上架状态。
    if (((Number) row.get("product_status")).intValue() != 1
        || ((Number) row.get("status")).intValue() != 1)
      throw new BizException("PRODUCT_OFF_SHELF", "商品已下架", 409);
    // 3. 以原金额和规格规则返回调用方所需的SKU事实。
    return new SkuSummaryVO(
        ((Number) Objects.requireNonNull(row.get("sku_id"))).longValue(),
        ((Number) Objects.requireNonNull(row.get("product_id"))).longValue(),
        (String) Objects.requireNonNull(row.get("name")),
        (String) Objects.requireNonNull(row.get("sku_code")),
        valueCodec.formatPrice((BigDecimal) row.get("price")),
        valueCodec.decodeSkuSpec(row.get("spec_json")));
  }

  /** 返回历史热度及当前模式启用的在线统计。 */
  @Override
  public HotStatVO hot(Long productId) {
    // 1. 先确认商品存在。
    catalogReader.requireProduct(productId);
    // 2. 读取原历史来源统计。
    List<HotStatVO> rows =
        productSqlMapper.findHotStat(productId).stream().map(this::hotView).toList();
    HotStatVO history = rows.isEmpty() ? new HotStatVO(productId, 0, 0, "0.000000") : rows.get(0);
    // 3. 按启用模式附加在线统计。
    return metricsEnabled
        ? new HotStatVO(
            history.productId,
            history.viewCount,
            history.searchCount,
            history.hotScore,
            history.sourceType,
            history.sourceRef,
            metrics.online(productId))
        : history;
  }

  /** 将原历史热度行映射为来源可追溯的视图。 */
  private HotStatVO hotView(Map<String, Object> row) {
    BigDecimal hotScore = (BigDecimal) row.get("hot_score");
    return new HotStatVO(
        ((Number) row.get("product_id")).longValue(),
        ((Number) row.get("view_count")).longValue(),
        ((Number) row.get("search_count")).longValue(),
        hotScore == null ? "0.000000" : hotScore.toPlainString(),
        row.get("source_type") == null ? "UNKNOWN" : String.valueOf(row.get("source_type")),
        (String) row.get("source_ref"));
  }

  /** 校验LEGACY/ONLINE开关与真实指标事务代理的原启动条件。 */
  @javax.annotation.PostConstruct
  public void validateMetricConfiguration() {
    if (!Set.of("LEGACY", "ONLINE").contains(metricSortMode)
        || ("ONLINE".equals(metricSortMode) && !metricsEnabled))
      throw new IllegalStateException("METRIC_SORT_MODE_INVALID");
    if (metricsEnabled
        && "ONLINE".equals(metricSortMode)
        && searchEnabled
        && productKeywordSearch == null)
      throw new IllegalStateException("METRIC_ONLINE_SEARCH_COMPONENT_REQUIRED");
    if (metricsEnabled
        && (metrics == null
            || transactionManager == null
            || !org.springframework.aop.support.AopUtils.isAopProxy(metrics)))
      throw new IllegalStateException("METRIC_TRANSACTION_PROXY_REQUIRED");
  }

  /** 判断当前排序是否进入显式ONLINE指标路径。 */
  private boolean onlineRank(String sort) {
    return metricsEnabled
        && "ONLINE".equals(metricSortMode)
        && ("sales".equals(sort) || "hot".equals(sort));
  }

  /** 保留指标登记依赖故障的错误码、消息与原cause。 */
  private static BizException metricUnavailable(Throwable cause) {
    BizException failure =
        new BizException(
            "PRODUCT_METRICS_UNAVAILABLE", "Product metrics could not be registered", 503);
    failure.initCause(cause);
    return failure;
  }
}
