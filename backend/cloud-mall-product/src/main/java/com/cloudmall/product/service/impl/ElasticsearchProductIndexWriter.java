package com.cloudmall.product.service.impl;

import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.cloudmall.product.service.ProductIndexWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    if (indexExists()) return;
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
    CreateIndexRequest request = new CreateIndexRequest(indexName);
    request.mapping(Map.of("properties", fields));
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
    return client.count(new CountRequest(indexName), RequestOptions.DEFAULT).getCount();
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

  @Override
  /** 执行 delete 相关操作。 */
  public void delete(long productId) throws Exception {
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
