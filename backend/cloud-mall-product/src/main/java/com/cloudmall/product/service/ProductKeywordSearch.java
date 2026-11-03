package com.cloudmall.product.service;

import com.cloudmall.product.domain.po.ProductSearchHit;
import com.cloudmall.product.domain.po.ProductSearchPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.core.TimeValue;
import org.apache.lucene.search.TotalHits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 对非空商品关键词执行有界 ES 搜索并保留精确总数。 */
@Service
@SuppressWarnings("deprecation")
public class ProductKeywordSearch {
  /** V2 projection and ONLINE sort admission are separate reviewed switches. */
  @Value("${cloudmall.product.metrics.enabled:false}") private boolean metricsEnabled;
  /** LEGACY keeps the original imported ranking fields. */
  @Value("${cloudmall.product.metrics.sort-mode:LEGACY}") private String metricSortMode="LEGACY";
  /** Per-request transport deadline, always removed in finally and never shared across callers. */
  private final ThreadLocal<Long> requestDeadline=new ThreadLocal<>();
  /** 已冻结的商品物理索引名称。 */
  private final String indexName;
  /** Elasticsearch 7.17 查询客户端。 */
  private final RestHighLevelClient client;
  /** 校验实际索引字段类型的 JSON 解析器。 */
  private final ObjectMapper objectMapper;
  /** 区分 ES 查询耗时、失败数和命中规模的指标注册器。 */
  private final MeterRegistry meterRegistry;

  /** 创建商品关键词搜索客户端。 */
  public ProductKeywordSearch(RestHighLevelClient client, ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      @Value("${cloudmall.product.elasticsearch.index:cloudmall_product}") String indexName) {
    this.client = client;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.indexName = indexName;
  }

  /** 在同一次 ES 请求中完成关键词、过滤、排序、分页和精确计数。 */
  public ProductSearchPage search(String keyword, Long categoryId, Integer status,
      int offset, int pageSize, String sort) throws Exception {
    return search(keyword,categoryId,status,offset,pageSize,sort,0L);
  }

  /** An ONLINE attempt shares its original two-attempt five-second monotonic network budget. */
  public ProductSearchPage search(String keyword,Long categoryId,Integer status,int offset,int pageSize,String sort,long deadline)throws Exception {
    requestDeadline.set(deadline);
    try{return searchAttempt(keyword,categoryId,status,offset,pageSize,sort);}
    finally{requestDeadline.remove();}
  }

  private ProductSearchPage searchAttempt(String keyword,Long categoryId,Integer status,int offset,int pageSize,String sort)throws Exception {
    long startedAt = System.nanoTime();
    try {
      // 1. 每次先核对现存物理索引的必要映射，防止丢索引后返回误导性空页。
      verifyMapping();
      // 2. 将请求参数映射到固定白名单字段，按商品 ID 稳定打破同值排序。
      BoolQueryBuilder query = QueryBuilders.boolQuery()
          .must(QueryBuilders.multiMatchQuery(keyword, "name", "description"));
      if(metricsEnabled)query.mustNot(QueryBuilders.termQuery("tombstone",true)).filter(QueryBuilders.rangeQuery("productVersion").gte(0));
      if (categoryId != null) query.filter(QueryBuilders.termQuery("categoryId", categoryId));
      if (status != null) query.filter(QueryBuilders.termQuery("status", status));
      if (Integer.valueOf(1).equals(status))
        query.filter(QueryBuilders.termQuery("visible", true));
      SearchSourceBuilder source = new SearchSourceBuilder().query(query).from(offset)
          .size(pageSize).trackTotalHits(true).timeout(TimeValue.timeValueSeconds(2))
          .fetchSource(new String[] {"id", "categoryId", "name", "price", "status",
              "productVersion", "updatedAt", "metricVersion", "onlineSeriesId", "onlineViewCount",
              "onlineSearchCount", "onlineSoldCount", "onlineHotScore", "contentFingerprint", "onlineFingerprint"}, null);
      switch (sort == null ? "" : sort) {
        case "price_asc" -> source.sort("price", SortOrder.ASC);
        case "price_desc" -> source.sort("price", SortOrder.DESC);
        case "sales" -> {
          source.sort(metricsEnabled&&"ONLINE".equals(metricSortMode)?"onlineSoldCount":"viewCount", SortOrder.DESC);
          source.sort("updatedAt", SortOrder.DESC);
        }
        case "hot" -> {
          source.sort(metricsEnabled&&"ONLINE".equals(metricSortMode)?"onlineHotScore":"hotScore", SortOrder.DESC);
          source.sort("updatedAt", SortOrder.DESC);
        }
        default -> source.sort("updatedAt", SortOrder.DESC);
      }
      source.sort("id", SortOrder.DESC);
      // 3. ES 超时、分片失败和非精确总数都向调用者报告故障。
      SearchResponse response = client.search(new SearchRequest(indexName).source(source),
          requestOptions());
      if (response.isTimedOut() || response.getFailedShards() > 0
          || response.getHits() == null || response.getHits().getTotalHits() == null
          || response.getHits().getTotalHits().relation != TotalHits.Relation.EQUAL_TO)
        throw new IllegalStateException("商品 ES 查询超时、分片失败或总数不精确");
      List<ProductSearchHit> hits = new ArrayList<>();
      for (SearchHit hit : response.getHits().getHits()) hits.add(parseHit(hit));
      long total = response.getHits().getTotalHits().value;
      meterRegistry.summary("cloudmall.product.search.hits").record(total);
      meterRegistry.summary("cloudmall.product.search.returned").record(hits.size());
      return new ProductSearchPage(hits, total);
    } catch (Exception failure) {
      meterRegistry.counter("cloudmall.product.search.failures").increment();
      throw failure;
    } finally {
      meterRegistry.timer("cloudmall.product.search.es")
          .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }
  }

  /** 确认索引拥有完整查询字段且类型符合排序契约。 */
  private void verifyMapping() throws Exception {
    if (!indexName.matches("[a-z0-9_-]+"))
      throw new IllegalStateException("商品 ES 索引名无效");
    Request request = new Request("GET", "/" + indexName + "/_mapping");
    JsonNode mappings = objectMapper.readTree(EntityUtils.toByteArray(
        perform(request).getEntity()));
    JsonNode properties = mappings.path(indexName).path("mappings").path("properties");
    if(metricsEnabled){if(!"CM21_V1".equals(mappings.path(indexName).path("mappings").path("_meta").path("cloudmallProductProjection").asText()))throw new IllegalStateException("METRIC_INDEX_METADATA_INVALID");for(String name:List.of("metricVersion","onlineViewCount","onlineSearchCount","onlineSoldCount","onlineHotScore"))if(!"long".equals(properties.path(name).path("type").asText()))throw new IllegalStateException("METRIC_INDEX_MAPPING_INVALID");for(String name:List.of("onlineSeriesId","contentFingerprint","onlineFingerprint"))if(!"keyword".equals(properties.path(name).path("type").asText()))throw new IllegalStateException("METRIC_INDEX_MAPPING_INVALID");}
    for (String field : List.of("id", "categoryId", "name", "description", "price",
        "status", "visible", "productVersion", "updatedAt", "viewCount",
          "searchCount", "hotScore", "metricSourceType", "metricSourceRef")) {
      if (properties.path(field).isMissingNode())
        throw new IllegalStateException("商品 ES 索引缺少字段: " + field);
    }
    if (!"date".equals(properties.path("updatedAt").path("type").asText())
        || !"long".equals(properties.path("viewCount").path("type").asText())
        || !"scaled_float".equals(properties.path("price").path("type").asText()))
      throw new IllegalStateException("商品 ES 索引排序字段类型不匹配");
  }

  /** 严格解析用于 MySQL 事实校验的当前页投影。 */
  private static ProductSearchHit parseHit(SearchHit hit) {
    Map<String, Object> source = hit.getSourceAsMap();
    if (source == null) throw new IllegalStateException("商品 ES 命中缺少 source");
    return new ProductSearchHit(((Number) source.get("id")).longValue(),
        ((Number) source.get("categoryId")).longValue(),
        (String) source.get("name"), new BigDecimal(source.get("price").toString()),
        ((Number) source.get("status")).intValue(),
        ((Number) source.get("productVersion")).longValue(),
        ((Number) source.get("updatedAt")).longValue(),
        nullableLong(source,"metricVersion"),(String)source.get("onlineSeriesId"),
        nullableLong(source,"onlineViewCount"),nullableLong(source,"onlineSearchCount"),
        nullableLong(source,"onlineSoldCount"),nullableLong(source,"onlineHotScore"),
        (String)source.get("contentFingerprint"),(String)source.get("onlineFingerprint"));
  }
  /** Read the actual generation outside any database transaction, before and after the ES page. */
  public String indexUuid()throws Exception{
    verifyMapping();Request request=new Request("GET","/"+indexName+"/_settings");
    String uuid=objectMapper.readTree(EntityUtils.toByteArray(perform(request).getEntity())).path(indexName).path("settings").path("index").path("uuid").asText();
    if(uuid.isBlank())throw new IllegalStateException("METRIC_INDEX_UUID_MISSING");return uuid;
  }
  private static Long nullableLong(Map<String,Object> source,String name){Object value=source.get(name);return value==null?null:((Number)value).longValue();}

  /** Read a generation within the same request-wide monotonic budget. */
  public String indexUuid(long deadline)throws Exception{requestDeadline.set(deadline);try{return indexUuid();}finally{requestDeadline.remove();}}
  private RequestOptions requestOptions(){Long deadline=requestDeadline.get();if(deadline==null||deadline==0)return RequestOptions.DEFAULT;long remaining=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(remaining<=0)throw new IllegalStateException("METRIC_SEARCH_DEADLINE");int timeout=(int)Math.min(2000,remaining);return RequestOptions.DEFAULT.toBuilder().setRequestConfig(org.apache.http.client.config.RequestConfig.custom().setConnectTimeout(timeout).setConnectionRequestTimeout(timeout).setSocketTimeout(timeout).build()).build();}
  private org.elasticsearch.client.Response perform(Request request)throws Exception{request.setOptions(requestOptions());return client.getLowLevelClient().performRequest(request);}

}
