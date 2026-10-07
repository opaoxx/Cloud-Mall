package com.cloudmall.common.messaging;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 为库存受理和订单消费生成相同的秒杀请求指纹。 */
public final class SeckillRequestFingerprint {
  /** 递归排序 JSON Map 字段，避免地址字段顺序导致虚假冲突。 */
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  /** 工具类不允许实例化。 */
  private SeckillRequestFingerprint() {}

  /** 规范化原地址快照；必须是 JSON 对象。 */
  public static String canonicalAddress(String addressSnapshot) {
    try {
      Object parsed =
          MAPPER
              .readerFor(Object.class)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .readValue(addressSnapshot);
      if (!(parsed instanceof Map<?, ?>)) throw new IllegalArgumentException("秒杀地址快照必须是 JSON 对象");
      return MAPPER.writeValueAsString(normalize(parsed));
    } catch (JsonProcessingException malformedAddress) {
      throw new IllegalArgumentException("秒杀地址快照不是合法 JSON", malformedAddress);
    }
  }

  /** 递归排序对象键并消除十进制数末尾无意义的零。 */
  private static Object normalize(Object value) {
    if (value instanceof Map<?, ?> object) {
      Map<String, Object> sorted = new TreeMap<>();
      object.forEach((key, nested) -> sorted.put(String.valueOf(key), normalize(nested)));
      return sorted;
    }
    if (value instanceof List<?> array) {
      List<Object> normalized = new ArrayList<>(array.size());
      for (Object item : array) normalized.add(normalize(item));
      return normalized;
    }
    if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros();
    return value;
  }

  /** 用活动、SKU、用户、数量和规范化地址生成不可变 SHA-256 请求哈希。 */
  public static String hash(
      long activityId, long skuId, long userId, int quantity, String addressSnapshot) {
    if (activityId <= 0 || skuId <= 0 || userId <= 0 || quantity <= 0)
      throw new IllegalArgumentException("秒杀请求维度必须为正数");
    String canonical =
        "v1\n" + activityId + "\n" + skuId + "\n" + userId + "\n" + quantity
            + "\n" + canonicalAddress(addressSnapshot);
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException unavailableSha256) {
      throw new IllegalStateException("JDK 缺少 SHA-256", unavailableSha256);
    }
  }

  /** 同一库存受理订单在投递和重放时保持固定事件号。 */
  public static String eventId(String orderNo) {
    if (orderNo == null || orderNo.isBlank())
      throw new IllegalArgumentException("秒杀订单号不能为空");
    return "seckill-order:" + orderNo;
  }
}
