package com.cloudmall.product.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class ProductMessagingConfigurationTest {
  @Test
  void listenerPreservesJsonAndLegacyJavaBytesWithoutDeserialization() {
    byte[] json = "{\"eventId\":\"stable\"}".getBytes(StandardCharsets.UTF_8);
    byte[] legacyJava = new byte[] {(byte) 0xac, (byte) 0xed, 0, 5};
    assertArrayEquals(json, converted(json, "application/json"));
    assertArrayEquals(legacyJava,
        converted(legacyJava, "application/x-java-serialized-object"));
  }

  private static byte[] converted(byte[] body, String contentType) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(contentType);
    return (byte[]) ProductMessagingConfiguration.rawBodyConverter()
        .fromMessage(new Message(body, properties));
  }
}
