package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.cloudmall.product.service.impl.index.ElasticsearchProductIndexWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.IndicesClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.rest.RestStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("deprecation")
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

  @Test
  void hiddenProductUsesTheMySqlVersionAndVisibleTombstone() throws Exception {
    RestHighLevelClient client = mock(RestHighLevelClient.class);
    IndicesClient indices = mock(IndicesClient.class);
    when(client.indices()).thenReturn(indices);
    when(indices.exists(
            any(GetIndexRequest.class), eq(org.elasticsearch.client.RequestOptions.DEFAULT)))
        .thenReturn(true);
    ElasticsearchProductIndexWriter writer =
        new ElasticsearchProductIndexWriter(client, new ObjectMapper(), "products");

    writer.upsert(
        new ProductIndexDocument(42L, 3L, "键盘", null, "desc", new BigDecimal("99.00"), 0, 7L));

    ArgumentCaptor<IndexRequest> request = ArgumentCaptor.forClass(IndexRequest.class);
    org.mockito.Mockito.verify(client)
        .index(request.capture(), eq(org.elasticsearch.client.RequestOptions.DEFAULT));
    assertEquals("42", request.getValue().id());
    assertEquals(8L, request.getValue().version());
    assertEquals(VersionType.EXTERNAL_GTE, request.getValue().versionType());
    assertFalse(
        new ObjectMapper()
            .readTree(request.getValue().source().utf8ToString())
            .get("visible")
            .asBoolean());
    assertEquals(
        7L,
        new ObjectMapper()
            .readTree(request.getValue().source().utf8ToString())
            .get("productVersion")
            .asLong());
  }

  @Test
  void partialBulkFailureMustLeaveTheRebuildCursorUncommitted() throws Exception {
    RestHighLevelClient client = mock(RestHighLevelClient.class);
    IndicesClient indices = mock(IndicesClient.class);
    when(client.indices()).thenReturn(indices);
    when(indices.exists(
            any(GetIndexRequest.class), eq(org.elasticsearch.client.RequestOptions.DEFAULT)))
        .thenReturn(true);
    BulkResponse response = mock(BulkResponse.class);
    BulkItemResponse applied = mock(BulkItemResponse.class);
    BulkItemResponse failed = mock(BulkItemResponse.class);
    when(failed.isFailed()).thenReturn(true);
    when(failed.status()).thenReturn(RestStatus.SERVICE_UNAVAILABLE);
    when(failed.getFailureMessage()).thenReturn("ES unavailable");
    when(response.getItems()).thenReturn(new BulkItemResponse[] {applied, failed});
    when(client.bulk(any(BulkRequest.class), eq(org.elasticsearch.client.RequestOptions.DEFAULT)))
        .thenReturn(response);
    ElasticsearchProductIndexWriter writer =
        new ElasticsearchProductIndexWriter(client, new ObjectMapper(), "products");

    assertThrows(
        IllegalStateException.class,
        () ->
            writer.upsertBatch(
                List.of(
                    new ProductIndexDocument(
                        41L, 3L, "商品甲", null, null, new BigDecimal("10.00"), 1, 1L),
                    new ProductIndexDocument(
                        42L, 3L, "商品乙", null, null, new BigDecimal("20.00"), 1, 1L))));

    ArgumentCaptor<BulkRequest> request = ArgumentCaptor.forClass(BulkRequest.class);
    org.mockito.Mockito.verify(client)
        .bulk(request.capture(), eq(org.elasticsearch.client.RequestOptions.DEFAULT));
    assertEquals(2, request.getValue().numberOfActions());
  }
}
