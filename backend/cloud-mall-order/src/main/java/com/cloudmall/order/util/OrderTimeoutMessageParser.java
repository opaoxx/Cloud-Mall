package com.cloudmall.order.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** 只解析原超时消息格式，不判断期限、不访问数据源、不修改共享JSON配置。 */
public final class OrderTimeoutMessageParser {
  /** 禁止实例化无状态消息解析工具。 */
  private OrderTimeoutMessageParser() {}

  /**
   * 严格解码原UTF-8，按原JSON/legacy分支保留订单号文本。
   *
   * @param objectMapper 原服务共享JSON配置，仅建立本次reader
   * @param payload 原消息字节，不按消息头猜测编码或重写正文
   * @return 原订单号文本，是否可关单由Service读取数据库后决定
   * @throws IllegalArgumentException 编码或已识别JSON结构非法，保留原cause分类
   */
  public static String orderNo(ObjectMapper objectMapper, byte[] payload) {
    // 1. 严格解码消息字节，拒绝损坏输入，保留原UTF-8异常。
    String rawPayload;
    try {
      // 解码先保原字节含义，损坏UTF8不得用替代字符猜测身份或降级旧格式。
      rawPayload =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(payload))
              .toString();
    } catch (CharacterCodingException invalidUtf8) {
      throw new IllegalArgumentException("订单超时事件不是合法UTF-8", invalidUtf8);
    }
    String orderNo;
    try {
      JsonNode event =
          objectMapper
              .reader()
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
              .readTree(rawPayload);
      if (event.isTextual()) {
        orderNo = event.asText();
      } else if (event.isIntegralNumber() && rawPayload.trim().matches("[0-9]+")) {
        // 旧裸数字String会被JSON读成整数；只认整份正文，避免long/浮点转换改写身份。
        orderNo = rawPayload.trim();
      } else if (event.isObject()
          && hasNonblankText(event, "eventType")
          && "ORDER_TIMEOUT".equals(event.path("eventType").textValue())
          && hasNonblankText(event, "eventId")
          && hasNonblankText(event, "orderNo")
          && hasNonblankText(event, "expireAt")) {
        orderNo = event.path("orderNo").textValue();
      } else {
        throw new IllegalArgumentException("订单超时事件格式无效");
      }
    } catch (MismatchedInputException invalidJsonRoot) {
      // 多根或重复键JSON不是旧订单号文本；保留解析证据，拒绝进入legacy或业务。
      throw new IllegalArgumentException("订单超时事件格式无效", invalidJsonRoot);
    } catch (com.fasterxml.jackson.core.JsonProcessingException malformedLegacyMessage) {
      orderNo = rawPayload.trim();
    }
    // 2. 原文解析结果不额外归一化，不以消息期限授权关单。
    return orderNo;
  }

  /** 必填原生文本须非空白且代理对完整；JSON转义不保证Unicode完整性，不替换或归一化。 */
  private static boolean hasNonblankText(JsonNode event, String fieldName) {
    JsonNode fieldValue = event.path(fieldName);
    if (!fieldValue.isTextual() || fieldValue.textValue().isBlank()) return false;
    String text = fieldValue.textValue();
    // 逐UTF16单元线性检查，高代理与紧邻低代理同时消费，孤立低代理不得放行。
    for (int position = 0; position < text.length(); position++) {
      char codeUnit = text.charAt(position);
      if (Character.isHighSurrogate(codeUnit)) {
        if (position + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(position + 1)))
          return false;
        position++;
      } else if (Character.isLowSurrogate(codeUnit)) return false;
    }
    return true;
  }
}
