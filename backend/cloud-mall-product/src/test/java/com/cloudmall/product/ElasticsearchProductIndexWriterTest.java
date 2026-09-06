package com.cloudmall.product;

import com.cloudmall.product.service.impl.ElasticsearchProductIndexWriter;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.rest.RestStatus;
import org.junit.jupiter.api.Test;

class ElasticsearchProductIndexWriterTest {
  @Test
  void deletingMissingDocumentIsIdempotent() throws Exception {
    RestHighLevelClient client = mock(RestHighLevelClient.class);
    doThrow(new ElasticsearchStatusException("missing", RestStatus.NOT_FOUND))
        .when(client)
        .delete(any(DeleteRequest.class), eq(org.elasticsearch.client.RequestOptions.DEFAULT));
    ElasticsearchProductIndexWriter writer =
        new ElasticsearchProductIndexWriter(client, new ObjectMapper(), "products");

    assertDoesNotThrow(() -> writer.delete(42L));
  }

  @Test
  void deletingOnOtherEsFailureStillFails() throws Exception {
    RestHighLevelClient client = mock(RestHighLevelClient.class);
    doThrow(new ElasticsearchStatusException("server error", RestStatus.INTERNAL_SERVER_ERROR))
        .when(client)
        .delete(any(DeleteRequest.class), eq(org.elasticsearch.client.RequestOptions.DEFAULT));
    ElasticsearchProductIndexWriter writer =
        new ElasticsearchProductIndexWriter(client, new ObjectMapper(), "products");

    assertThrows(ElasticsearchStatusException.class, () -> writer.delete(42L));
  }
}
