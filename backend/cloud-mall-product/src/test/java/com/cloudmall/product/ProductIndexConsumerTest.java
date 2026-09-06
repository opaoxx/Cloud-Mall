package com.cloudmall.product;

import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.cloudmall.product.service.ProductIndexConsumer;
import com.cloudmall.product.service.ProductIndexDocumentLoader;
import com.cloudmall.product.service.ProductIndexWriter;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductIndexConsumerTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final ProductIndexDocumentLoader loader = mock(ProductIndexDocumentLoader.class);
  private final ProductIndexWriter writer = mock(ProductIndexWriter.class);
  private final ProductIndexConsumer consumer = new ProductIndexConsumer(mapper, loader, writer);

  @Test
  void changedEventReadsMySqlAndUpsertsDeterministicDocument() throws Exception {
    ProductIndexDocument document =
        new ProductIndexDocument(42L, 3L, "键盘", null, "desc", new BigDecimal("99.00"), 1);
    when(loader.load(42L)).thenReturn(Optional.of(document));

    consumer.consume(event("PRODUCT_CHANGED", 42L));

    verify(loader).load(42L);
    verify(writer).upsert(document);
    verifyNoMoreInteractions(writer);
  }

  @Test
  void repeatedChangedEventIsSafeAndMissingFactRemovesStaleIndex() throws Exception {
    when(loader.load(42L)).thenReturn(Optional.empty());

    consumer.consume(event("PRODUCT_CHANGED", 42L));
    consumer.consume(event("PRODUCT_CHANGED", 42L));

    verify(writer, times(2)).delete(42L);
  }

  @Test
  void deletedEventDeletesIndexWithoutReadingAnotherService() throws Exception {
    consumer.consume(event("PRODUCT_DELETED", 42L));

    verify(writer).delete(42L);
    verifyNoInteractions(loader);
  }

  @Test
  void categoryEventOnSharedProductQueueIsIgnored() throws Exception {
    consumer.consume(categoryEvent("CATEGORY_CHANGED", 42L));

    verifyNoInteractions(loader, writer);
  }

  @Test
  void categoryEventStillRequiresAValidEnvelope() throws Exception {
    byte[] malformed =
        mapper.writeValueAsBytes(
            Map.of(
                "eventId",
                "event-1",
                "eventType",
                "CATEGORY_CHANGED",
                "traceId",
                "trace-1",
                "payload",
                Map.of("categoryId", 42L)));

    assertThrows(IllegalArgumentException.class, () -> consumer.consume(malformed));
    verifyNoInteractions(loader, writer);
  }

  @Test
  void productEventRejectsMissingOrInconsistentEnvelopeFields() throws Exception {
    byte[] missingOccurredAt =
        mapper.writeValueAsBytes(
            Map.of(
                "eventId",
                "event-1",
                "eventType",
                "PRODUCT_CHANGED",
                "traceId",
                "trace-1",
                "businessKey",
                Map.of("name", "productId", "value", 42L),
                "payload",
                Map.of("productId", 42L),
                "productId",
                42L));
    byte[] inconsistentPayload =
        mapper.writeValueAsBytes(
            Map.of(
                "eventId",
                "event-1",
                "eventType",
                "PRODUCT_CHANGED",
                "occurredAt",
                "2026-09-04T12:00:00+08:00",
                "traceId",
                "trace-1",
                "businessKey",
                Map.of("name", "productId", "value", 42L),
                "payload",
                Map.of("productId", 43L),
                "productId",
                42L));

    assertThrows(IllegalArgumentException.class, () -> consumer.consume(missingOccurredAt));
    assertThrows(IllegalArgumentException.class, () -> consumer.consume(inconsistentPayload));
    verifyNoInteractions(loader, writer);
  }

  @Test
  void malformedOrFailedEventIsNotSwallowed() throws Exception {
    when(loader.load(42L)).thenThrow(new IllegalStateException("mysql down"));

    assertThrows(
        IllegalStateException.class, () -> consumer.consume(event("PRODUCT_CHANGED", 42L)));
  }

  private byte[] event(String type, long productId) throws Exception {
    return mapper.writeValueAsBytes(
        Map.of(
            "eventId",
            "event-1",
            "eventType",
            type,
            "occurredAt",
            "2026-09-04T12:00:00+08:00",
            "productId",
            productId,
            "businessKey",
            Map.of("name", "productId", "value", productId),
            "traceId",
            "trace-1",
            "payload",
            Map.of("productId", productId)));
  }

  private byte[] categoryEvent(String type, long categoryId) throws Exception {
    return mapper.writeValueAsBytes(
        Map.of(
            "eventId",
            "event-1",
            "eventType",
            type,
            "occurredAt",
            "2026-09-04T12:00:00+08:00",
            "categoryId",
            categoryId,
            "businessKey",
            Map.of("name", "categoryId", "value", categoryId),
            "traceId",
            "trace-1",
            "payload",
            Map.of("categoryId", categoryId)));
  }
}
