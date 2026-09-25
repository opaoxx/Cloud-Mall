package com.cloudmall.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** 库存与订单两端共享的秒杀指纹契约。 */
class SeckillRequestFingerprintTest {
  @Test
  void addressFieldOrderDoesNotChangeTheRequestHash() {
    String first = SeckillRequestFingerprint.hash(
        11L, 22L, 33L, 1, "{\"city\":\"上海\",\"contact\":{\"phone\":\"123\",\"name\":\"甲\"}}");
    String reordered = SeckillRequestFingerprint.hash(
        11L, 22L, 33L, 1, "{\"contact\":{\"name\":\"甲\",\"phone\":\"123\"},\"city\":\"上海\"}");

    assertEquals(first, reordered);
    assertEquals(
        SeckillRequestFingerprint.canonicalAddress("{\"building\":1.00}"),
        SeckillRequestFingerprint.canonicalAddress("{\"building\":1.0}"));
    assertNotEquals(first, SeckillRequestFingerprint.hash(
        12L, 22L, 33L, 1, "{\"city\":\"上海\",\"contact\":{\"phone\":\"123\",\"name\":\"甲\"}}"));
    assertEquals("seckill-order:order-1", SeckillRequestFingerprint.eventId("order-1"));
  }
}
