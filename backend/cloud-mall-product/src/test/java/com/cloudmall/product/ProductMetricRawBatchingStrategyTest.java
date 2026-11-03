package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.product.util.ProductMetricRawBatchingStrategy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class ProductMetricRawBatchingStrategyTest {
  /** Marker-bearing physical bytes and tag must survive consumer collection unchanged. */
  @Test
  void neverSplitsProducerMarkerOrMutatesBodyHeadersTag() {
    ProductMetricRawBatchingStrategy strategy = new ProductMetricRawBatchingStrategy();
    MessageProperties properties = new MessageProperties();
    properties.setHeader("springBatchFormat", "lengthHeader4");
    properties.setDeliveryTag(17L);
    byte[] body = "{\"eventId\":\"one\"}".getBytes(StandardCharsets.UTF_8);
    Message message = new Message(body, properties);
    AtomicInteger emitted = new AtomicInteger();
    assertFalse(strategy.canDebatch(properties));
    assertThrows(
        UnsupportedOperationException.class,
        () -> strategy.deBatch(message, ignored -> emitted.incrementAndGet()));
    assertEquals(0, emitted.get());
    assertSame(body, message.getBody());
    assertEquals("lengthHeader4", properties.getHeader("springBatchFormat"));
    assertEquals(17L, properties.getDeliveryTag());
    assertFalse(strategy.canDebatch(new MessageProperties()));
  }

  /** Installing this consumer strategy on a producer cannot silently buffer or release messages. */
  @Test
  void rejectsAllProducerEntryPoints() {
    ProductMetricRawBatchingStrategy strategy = new ProductMetricRawBatchingStrategy();
    assertThrows(
        UnsupportedOperationException.class,
        () -> strategy.addToBatch("exchange", "route", new Message(new byte[0])));
    assertThrows(UnsupportedOperationException.class, strategy::nextRelease);
    assertThrows(UnsupportedOperationException.class, strategy::releaseBatches);
  }
}
