package com.cloudmall.product.service.impl;

import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.cloudmall.product.service.ProductIndexWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
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
  /** 执行 upsert 相关操作。 */
  public void upsert(ProductIndexDocument document) throws Exception {
    // 1. 接收并整理 upsert 的业务请求。
    // 2. 执行 upsert 的核心业务校验与状态处理。
    // 3. 返回 upsert 的处理结果。
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("id", document.id());
    source.put("categoryId", document.categoryId());
    source.put("name", document.name());
    source.put("mainImage", document.mainImage());
    source.put("description", document.description());
    source.put("price", document.price());
    source.put("status", document.status());
    source.put("published", document.status() == 1);
    client.index(
        new IndexRequest(indexName)
            .id(String.valueOf(document.id()))
            .source(objectMapper.writeValueAsBytes(source), XContentType.JSON),
        RequestOptions.DEFAULT);
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
