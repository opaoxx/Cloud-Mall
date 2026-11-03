package com.cloudmall.product.service.impl;

import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.cloudmall.product.service.ProductIndexWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.ZoneId;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@SuppressWarnings("deprecation")
public class ElasticsearchProductIndexWriter implements ProductIndexWriter {
  /** V2 is enabled only with the reviewed new online series. */
  @Value("${cloudmall.product.metrics.enabled:false}") private boolean metricsEnabled;
  /** Closing readiness is independent of the failed ES write transaction. */
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private com.cloudmall.product.service.ProductMetricService metrics;
  /** 保存 client 的业务状态或配置。 */
  private final RestHighLevelClient client;

  /** 保存 objectMapper 的业务状态或配置。 */
  private final ObjectMapper objectMapper;

  /** 保存 indexName 的业务状态或配置。 */
  private final String indexName;

  /** 创建 ElasticsearchProductIndexWriter 实例。 */
  public ElasticsearchProductIndexWriter(
      RestHighLevelClient client,
      ObjectMapper objectMapper,
      @Value("${cloudmall.product.elasticsearch.index:cloudmall_product}") String indexName) {
    this.client = client;
    this.objectMapper = objectMapper;
    this.indexName = indexName;
  }

  @Override
  /** 使用 MySQL 商品版本阻止旧事件与重建批次倒序覆盖新文档。 */
  public void upsert(ProductIndexDocument document) throws Exception {
    if(metricsEnabled){ensureIndex();client.update(v2Request(document.id(),content(document),metric(document.onlineStat())),RequestOptions.DEFAULT);return;}
    // 1. 首次写入前准备明确字段映射。
    ensureIndex();
    // 2. external_gte 允许同版本幂等重放，并拒绝低版本覆盖。
    try {
      client.index(indexRequest(document), RequestOptions.DEFAULT);
    } catch (ElasticsearchStatusException versionConflict) {
      if (versionConflict.status() != RestStatus.CONFLICT
          || !hasTrustedNewerVersion(document)) throw versionConflict;
    }
  }

  /** ES Bulk 的任何非版本冲突失败都保留原游标供整批重试。 */
  @Override
  public void upsertBatch(List<ProductIndexDocument> documents) throws Exception {
    if (documents == null || documents.isEmpty()) return;
    if(metricsEnabled){if(documents.size()>500)throw new IllegalArgumentException("METRIC_CONTENT_BULK_LIMIT");ensureIndex();BulkRequest batch=new BulkRequest().setRefreshPolicy(org.elasticsearch.action.support.WriteRequest.RefreshPolicy.WAIT_UNTIL);for(ProductIndexDocument document:documents)batch.add(v2Request(document.id(),content(document),metric(document.onlineStat())).setRefreshPolicy(org.elasticsearch.action.support.WriteRequest.RefreshPolicy.NONE));requireBulk(client.bulk(batch,RequestOptions.DEFAULT),documents.size());return;}
    if (documents.size() > 500) throw new IllegalArgumentException("ES Bulk 批次不能超过 500");
    // 1. 为当前代次准备索引，并按同一版本协议组装批量请求。
    ensureIndex();
    BulkRequest request = new BulkRequest();
    for (ProductIndexDocument document : documents) request.add(indexRequest(document));
    // 2. 检查每个 item；部分成功时抛错让持久游标停在原批次。
    BulkResponse response = client.bulk(request, RequestOptions.DEFAULT);
    BulkItemResponse[] items = response.getItems();
    if (items.length != documents.size())
      throw new IllegalStateException("ES Bulk 响应条数与请求不一致");
    for (int itemIndex = 0; itemIndex < items.length; itemIndex++) {
      BulkItemResponse item = items[itemIndex];
      if (!item.isFailed()) continue;
      if (item.status() == RestStatus.CONFLICT
          && hasTrustedNewerVersion(documents.get(itemIndex))) continue;
      throw new IllegalStateException(
          "ES Bulk 商品索引失败，productId=" + documents.get(itemIndex).id()
              + "，reason=" + item.getFailureMessage());
    }
  }

  /** 创建商品索引映射；并发实例创建同名索引时重新读取事实。 */
  @Override
  public void ensureIndex() throws Exception {
    if(metricsEnabled&&!indexName.matches("cm21_[0-9a-f]{32}_product"))throw new IllegalStateException("METRIC_INDEX_NAME_INVALID");
    if (indexExists()) {if(metricsEnabled)verifyV2();return;}
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", Map.of("type", "long"));
    fields.put("categoryId", Map.of("type", "long"));
    fields.put("name", Map.of("type", "text"));
    fields.put("mainImage", Map.of("type", "keyword"));
    fields.put("description", Map.of("type", "text"));
    fields.put("price", Map.of("type", "scaled_float", "scaling_factor", 100));
    fields.put("status", Map.of("type", "integer"));
    fields.put("published", Map.of("type", "boolean"));
    fields.put("visible", Map.of("type", "boolean"));
    fields.put("productVersion", Map.of("type", "long"));
    fields.put("updatedAt", Map.of("type", "date", "format", "epoch_millis"));
    fields.put("viewCount", Map.of("type", "long"));
    fields.put("searchCount", Map.of("type", "long"));
    fields.put("hotScore", Map.of("type", "scaled_float", "scaling_factor", 1000000));
    fields.put("metricSourceType", Map.of("type", "keyword"));
    fields.put("metricSourceRef", Map.of("type", "keyword"));
    CreateIndexRequest request = new CreateIndexRequest(indexName);
    if(metricsEnabled){for(String name:List.of("metricVersion","onlineViewCount","onlineSearchCount","onlineSoldCount","onlineHotScore"))fields.put(name,Map.of("type","long"));for(String name:List.of("onlineSeriesId","contentFingerprint","onlineFingerprint"))fields.put(name,Map.of("type","keyword"));fields.put("tombstone",Map.of("type","boolean"));}
    request.mapping(metricsEnabled?Map.of("properties",fields,"_meta",Map.of("cloudmallProductProjection","CM21_V1")):Map.of("properties", fields));
    request.settings(Map.of("index", Map.of("max_result_window", 100100)));
    try {
      client.indices().create(request, RequestOptions.DEFAULT);
    } catch (ElasticsearchStatusException concurrentCreate) {
      if (!indexExists()) throw concurrentCreate;
    }
  }

  /** 检查目标索引是否存在，供完成后的丢失探测使用。 */
  @Override
  public boolean indexExists() throws Exception {
    return client.indices().exists(new GetIndexRequest(indexName), RequestOptions.DEFAULT);
  }

  /** 读取 ES 当前文档数，供限速重建完成后的事实核对。 */
  @Override
  public long countDocuments() throws Exception {
    if (!indexExists()) return 0L;
    CountRequest request=new CountRequest(indexName);
    if(metricsEnabled)request.query(org.elasticsearch.index.query.QueryBuilders.boolQuery().mustNot(org.elasticsearch.index.query.QueryBuilders.termQuery("tombstone",true)).filter(org.elasticsearch.index.query.QueryBuilders.rangeQuery("productVersion").gte(0)));
    return client.count(request, RequestOptions.DEFAULT).getCount();
  }

  /** 为单条和 Bulk 构造完全一致的带版本 ES 写入。 */
  private IndexRequest indexRequest(ProductIndexDocument document) throws Exception {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("id", document.id());
    source.put("categoryId", document.categoryId());
    source.put("name", document.name());
    source.put("mainImage", document.mainImage());
    source.put("description", document.description());
    source.put("price", document.price());
    source.put("status", document.status());
    source.put("published", document.status() == 1);
    source.put("visible", document.status() == 1);
    source.put("productVersion", document.version());
    source.put("updatedAt", document.updatedAt()
        .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
    source.put("viewCount", document.viewCount());
    source.put("searchCount", document.searchCount());
    source.put("hotScore", document.hotScore());
    source.put("metricSourceType", document.metricSourceType());
    source.put("metricSourceRef", document.metricSourceRef());
    return new IndexRequest(indexName)
        .id(String.valueOf(document.id()))
        .source(objectMapper.writeValueAsBytes(source), XContentType.JSON)
        .version(Math.addExact(document.version(), 1L))
        .versionType(VersionType.EXTERNAL_GTE);
  }

  /** 409 只有在目标文档已带可信新版本时才能视为旧写入。 */
  private boolean hasTrustedNewerVersion(ProductIndexDocument document) throws Exception {
    GetResponse existing =
        client.get(new GetRequest(indexName, String.valueOf(document.id())), RequestOptions.DEFAULT);
    if (!existing.isExists()
        || existing.getSourceAsMap() == null
        || !(existing.getSourceAsMap().get("productVersion") instanceof Number currentVersion)) {
      throw new IllegalStateException(
          "VERSION_SEMANTICS_INCOMPATIBLE: 现有 ES 文档没有可信 productVersion");
    }
    return currentVersion.longValue() >= document.version();
  }

  /** Read actual index UUID plus reviewed mapping metadata; a same-count replacement is a new generation. */
  public String indexUuid() throws Exception {
    verifyV2();
    org.elasticsearch.client.Request request=new org.elasticsearch.client.Request("GET","/"+indexName+"/_settings");
    com.fasterxml.jackson.databind.JsonNode root=objectMapper.readTree(org.apache.http.util.EntityUtils.toByteArray(client.getLowLevelClient().performRequest(request).getEntity()));
    String uuid=root.path(indexName).path("settings").path("index").path("uuid").asText();
    if(uuid.isBlank())throw new IllegalStateException("METRIC_INDEX_UUID_MISSING");return uuid;
  }

  /** Metric-only updates contain no content fields and cannot restore visibility or a tombstone. */
  public void upsertMetrics(List<ProductIndexDocument> documents) throws Exception {
    if(!metricsEnabled||documents.size()>50)throw new IllegalArgumentException("METRIC_PROJECTION_BATCH_INVALID");
    if(documents.isEmpty())return;ensureIndex();
    BulkRequest batch=new BulkRequest().setRefreshPolicy(org.elasticsearch.action.support.WriteRequest.RefreshPolicy.WAIT_UNTIL);
    for(ProductIndexDocument document:documents){if(document.onlineStat()==null)throw new IllegalStateException("METRIC_SNAPSHOT_MISSING");batch.add(v2Request(document.id(),null,metric(document.onlineStat())).setRefreshPolicy(org.elasticsearch.action.support.WriteRequest.RefreshPolicy.NONE));}
    requireBulk(client.bulk(batch,RequestOptions.DEFAULT),documents.size());
  }

  /** A trusted delete event writes a versioned tombstone and never physically removes metric history. */
  @Override
  public void delete(long productId,long eventVersion)throws Exception {
    if(!metricsEnabled){delete(productId);return;}
    if(productId<=0||eventVersion<0)throw new IllegalStateException("CONTENT_DELETE_VERSION_UNPROVEN");
    ensureIndex();
    Map<String,Object> deleted=new LinkedHashMap<>();deleted.put("id",productId);deleted.put("productVersion",eventVersion);deleted.put("tombstone",true);deleted.put("visible",false);deleted.put("published",false);
    deleted.put("contentFingerprint",fingerprint(deleted));
    try{client.update(v2Request(productId,deleted,null),RequestOptions.DEFAULT);}
    catch(Exception failure){if(metrics!=null)try{metrics.readiness(metrics.cut().indexUuid(),false);}catch(RuntimeException closingFailure){failure.addSuppressed(closingFailure);}throw failure;}
  }

  /** Canonical immutable content hash used by final-page admission as well as atomic ES writes. */
  public String contentFingerprint(ProductIndexDocument document)throws Exception{return String.valueOf(content(document).get("contentFingerprint"));}

  /** Canonical metric hash excludes delivery and aggregate update timestamps. */
  public String metricFingerprint(com.cloudmall.product.domain.po.ProductOnlineStatPO stat)throws Exception{return String.valueOf(metric(stat).get("onlineFingerprint"));}

  private Map<String,Object> content(ProductIndexDocument document)throws Exception{
    Map<String,Object> value=new LinkedHashMap<>();value.put("id",document.id());value.put("categoryId",document.categoryId());value.put("name",document.name());value.put("mainImage",document.mainImage());value.put("description",document.description());value.put("price",document.price().setScale(2).toPlainString());value.put("status",document.status());value.put("productVersion",document.version());value.put("updatedAt",document.updatedAt().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());value.put("viewCount",document.viewCount());value.put("searchCount",document.searchCount());value.put("hotScore",document.hotScore().setScale(6).toPlainString());value.put("metricSourceType",document.metricSourceType());value.put("metricSourceRef",document.metricSourceRef());value.put("published",document.status()==1);value.put("visible",document.status()==1);value.put("tombstone",false);value.put("contentFingerprint",fingerprint(value));return value;
  }

  private Map<String,Object> metric(com.cloudmall.product.domain.po.ProductOnlineStatPO stat)throws Exception{
    if(stat==null)return null;
    Map<String,Object> value=new LinkedHashMap<>();value.put("onlineSeriesId","cm21-online-v1");value.put("id",stat.productId);value.put("onlineViewCount",stat.viewCount);value.put("onlineSearchCount",stat.searchCount);value.put("onlineSoldCount",stat.soldCount);value.put("onlineHotScore",stat.hotScore);value.put("metricVersion",stat.metricVersion);value.put("onlineFingerprint",fingerprint(value));return value;
  }

  private String fingerprint(Map<String,Object> fields)throws Exception{return com.cloudmall.product.util.ProductMetricEvents.sha256(objectMapper.writeValueAsBytes(fields));}

  private org.elasticsearch.action.update.UpdateRequest v2Request(long id,Map<String,Object> content,Map<String,Object> metric){
    Map<String,Object> parameters=new LinkedHashMap<>();parameters.put("content",content);parameters.put("metric",metric);
    Map<String,Object> initial=new LinkedHashMap<>();initial.put("id",id);initial.put("productVersion",-1L);initial.put("metricVersion",-1L);initial.put("onlineSeriesId","cm21-online-v1");initial.put("visible",false);initial.put("published",false);initial.put("tombstone",false);
    String script="boolean changed=false; if(params.content!=null){def incoming=params.content; long current=ctx._source.productVersion==null?-1:ctx._source.productVersion; int oldPriority=ctx._source.tombstone==true?1:0; int priority=incoming.tombstone==true?1:0; if(priority==1 && incoming.productVersion<current){throw new IllegalArgumentException('CONTENT_DELETE_VERSION_UNPROVEN');} if(incoming.productVersion>current || (incoming.productVersion==current && priority>oldPriority)){for(def entry:incoming.entrySet()){ctx._source[entry.getKey()]=entry.getValue();} changed=true;} else if(incoming.productVersion==current && priority==oldPriority && ctx._source.contentFingerprint!=incoming.contentFingerprint){throw new IllegalArgumentException('CONTENT_VERSION_CONFLICT');}} if(params.metric!=null){def incoming=params.metric;if(incoming.onlineSeriesId!='cm21-online-v1'){throw new IllegalArgumentException('METRIC_SERIES_CONFLICT');} long current=ctx._source.metricVersion==null?-1:ctx._source.metricVersion;if(incoming.metricVersion>current){for(def entry:incoming.entrySet()){ctx._source[entry.getKey()]=entry.getValue();}changed=true;}else if(incoming.metricVersion==current && ctx._source.onlineFingerprint!=incoming.onlineFingerprint){throw new IllegalArgumentException('METRIC_VERSION_CONFLICT');}} if(!changed){ctx.op='none';}";
    return new org.elasticsearch.action.update.UpdateRequest(indexName,String.valueOf(id))
        .script(new org.elasticsearch.script.Script(org.elasticsearch.script.ScriptType.INLINE,"painless",script,parameters))
        .scriptedUpsert(true).upsert(initial).retryOnConflict(3)
        .setRefreshPolicy(org.elasticsearch.action.support.WriteRequest.RefreshPolicy.WAIT_UNTIL);
  }

  private static void requireBulk(BulkResponse response,int expected){if(response.getItems().length!=expected)throw new IllegalStateException("METRIC_BULK_COUNT");for(BulkItemResponse item:response.getItems())if(item.isFailed())throw new IllegalStateException("METRIC_BULK_ITEM_FAILED");}

  private void verifyV2()throws Exception{
    if(!indexName.matches("cm21_[0-9a-f]{32}_product"))throw new IllegalStateException("METRIC_INDEX_NAME_INVALID");
    org.elasticsearch.client.Request request=new org.elasticsearch.client.Request("GET","/"+indexName+"/_mapping");
    com.fasterxml.jackson.databind.JsonNode root=objectMapper.readTree(org.apache.http.util.EntityUtils.toByteArray(client.getLowLevelClient().performRequest(request).getEntity())).path(indexName).path("mappings");
    if(!"CM21_V1".equals(root.path("_meta").path("cloudmallProductProjection").asText()))throw new IllegalStateException("METRIC_INDEX_METADATA_INVALID");
    for(String field:List.of("metricVersion","onlineViewCount","onlineSearchCount","onlineSoldCount","onlineHotScore"))if(!"long".equals(root.path("properties").path(field).path("type").asText()))throw new IllegalStateException("METRIC_INDEX_MAPPING_INVALID");
    for(String field:List.of("onlineSeriesId","contentFingerprint","onlineFingerprint"))if(!"keyword".equals(root.path("properties").path(field).path("type").asText()))throw new IllegalStateException("METRIC_INDEX_MAPPING_INVALID");
    if(!"boolean".equals(root.path("properties").path("tombstone").path("type").asText()))throw new IllegalStateException("METRIC_INDEX_MAPPING_INVALID");
  }

  @Override
  /** 执行 delete 相关操作。 */
  public void delete(long productId) throws Exception {
    if(metricsEnabled)throw new IllegalStateException("CONTENT_DELETE_VERSION_UNPROVEN");
    // 1. 接收并整理 delete 的业务请求。
    // 2. 执行 delete 的核心业务校验与状态处理。
    // 3. 返回 delete 的处理结果。
    try {
      client.delete(
          new DeleteRequest(indexName, String.valueOf(productId)), RequestOptions.DEFAULT);
    } catch (ElasticsearchStatusException e) {
      if (e.status() != RestStatus.NOT_FOUND) throw e;
      // DELETE is idempotent: a missing document is already in the desired state.
    }
  }
}
