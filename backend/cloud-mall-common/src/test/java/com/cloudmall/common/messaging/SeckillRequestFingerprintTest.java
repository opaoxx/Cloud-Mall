package com.cloudmall.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 库存与订单两端共享的秒杀指纹契约。 */
class SeckillRequestFingerprintTest {
  @Test
  void loneUnicodeSurrogatesCannotBeReplacedWhileHashingAddresses() {
    String escapedHigh = "\\uD800";
    String escapedLow = "\\uDC00";
    String invalidAddress = "{\"name\":\"" + escapedHigh + "\"}";
    for (String address :
        List.of(
            invalidAddress,
            "{\"name\":\"" + escapedLow + "\"}",
            "{\"" + escapedHigh + "\":1}",
            "{\"nested\":{\"" + escapedLow + "\":1}}",
            "{\"values\":[\"" + escapedHigh + "\"]}",
            "{\"name\":\"" + escapedHigh + "x\"}",
            "{\"name\":\"" + (char) 0xD800 + "\"}")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> SeckillRequestFingerprint.canonicalAddress(address));
      assertThrows(
          IllegalArgumentException.class,
          () -> SeckillRequestFingerprint.hash(1, 2, 3, 1, address));
    }
  }

  @Test
  void validSurrogatePairsAndOrdinaryQuestionMarksRemainDistinctAndStable() {
    assertEquals(
        SeckillRequestFingerprint.hash(1, 2, 3, 1, "{\"name\":\"😀\"}"),
        SeckillRequestFingerprint.hash(1, 2, 3, 1, "{\"name\":\"\\uD83D\\uDE00\"}"));
    assertEquals(
        "{\"😀\":\"�?\"}",
        SeckillRequestFingerprint.canonicalAddress("{\"\\uD83D\\uDE00\":\"�?\"}"));
    assertNotEquals(
        SeckillRequestFingerprint.hash(1, 2, 3, 1, "{\"name\":\"�\"}"),
        SeckillRequestFingerprint.hash(1, 2, 3, 1, "{\"name\":\"?\"}"));
  }

  @Test
  void trailingAddressValuesCannotBeIgnoredByCanonicalizationOrHashing() {
    for (String invalidRoot : List.of("null", "[]", "1", "true", "\"text\"", "{invalid")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> SeckillRequestFingerprint.canonicalAddress(invalidRoot));
    }
    for (String suffix : List.of(" {}", " []", " null", " 1", " true", " garbage")) {
      String address = "{\"city\":\"上海\"}" + suffix;
      assertThrows(
          IllegalArgumentException.class,
          () -> SeckillRequestFingerprint.canonicalAddress(address),
          suffix);
      assertThrows(
          IllegalArgumentException.class,
          () -> SeckillRequestFingerprint.hash(1, 2, 3, 1, address),
          suffix);
    }
  }

  @Test
  void duplicateAddressFieldsCannotCollapseIntoTheLastValue() {
    for (String address :
        List.of(
            "{\"city\":\"上海\",\"city\":\"北京\"}",
            "{\"contact\":{\"name\":\"甲\",\"name\":\"乙\"}}",
            "{\"lines\":[{\"id\":1,\"id\":2}]}")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> SeckillRequestFingerprint.canonicalAddress(address));
      assertThrows(
          IllegalArgumentException.class,
          () -> SeckillRequestFingerprint.hash(1, 2, 3, 1, address));
    }
  }

  @Test
  void validCanonicalAddressAndExistingV1HashRemainUnchanged() {
    assertEquals(
        "7cdbf7f4f84bb97aab9d946c883668e779e6eb2592a7dd61bcdf410097a3e472",
        SeckillRequestFingerprint.hash(1, 2, 3, 1, " \n{}\t"));
    assertEquals(
        "{\"a\":[2,1],\"city\":\"上海😀\",\"n\":1}",
        SeckillRequestFingerprint.canonicalAddress(
            " {\"n\":1.00,\"city\":\"上海😀\",\"a\":[2,1]} \n"));
    assertNotEquals(
        SeckillRequestFingerprint.hash(1, 2, 3, 1, "{\"a\":[1,2]}"),
        SeckillRequestFingerprint.hash(1, 2, 3, 1, "{\"a\":[2,1]}"));
  }

  @Test
  void addressFieldOrderDoesNotChangeTheRequestHash() {
    String first =
        SeckillRequestFingerprint.hash(
            11L, 22L, 33L, 1, "{\"city\":\"上海\",\"contact\":{\"phone\":\"123\",\"name\":\"甲\"}}");
    String reordered =
        SeckillRequestFingerprint.hash(
            11L, 22L, 33L, 1, "{\"contact\":{\"name\":\"甲\",\"phone\":\"123\"},\"city\":\"上海\"}");

    assertEquals(first, reordered);
    assertEquals(
        SeckillRequestFingerprint.canonicalAddress("{\"building\":1.00}"),
        SeckillRequestFingerprint.canonicalAddress("{\"building\":1.0}"));
    assertNotEquals(
        first,
        SeckillRequestFingerprint.hash(
            12L, 22L, 33L, 1, "{\"city\":\"上海\",\"contact\":{\"phone\":\"123\",\"name\":\"甲\"}}"));
    assertEquals("seckill-order:order-1", SeckillRequestFingerprint.eventId("order-1"));
  }
}
