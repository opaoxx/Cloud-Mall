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
  private final RestHighLevelClient client;
  private final ObjectMapper objectMapper;
  private final String indexName;

  public ElasticsearchProductIndexWriter(
      RestHighLevelClient client,
      ObjectMapper objectMapper,
      @Value("${cloudmall.product.elasticsearch.index:cloudmall_product}") String indexName) {
    this.client = client;
    this.objectMapper = objectMapper;
    this.indexName = indexName;
  }

  @Override
  public void upsert(ProductIndexDocument document) throws Exception {
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
  public void delete(long productId) throws Exception {
    try {
      client.delete(
          new DeleteRequest(indexName, String.valueOf(productId)), RequestOptions.DEFAULT);
    } catch (ElasticsearchStatusException e) {
      if (e.status() != RestStatus.NOT_FOUND) throw e;
      // DELETE is idempotent: a missing document is already in the desired state.
    }
  }
}
