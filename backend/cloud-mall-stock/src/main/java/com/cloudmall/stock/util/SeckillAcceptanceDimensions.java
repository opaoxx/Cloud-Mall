package com.cloudmall.stock.util;

import java.math.BigDecimal;
import java.util.Map;

/** 原JDBC受理上下文的无损维度检查；不规范化字段或证明完整请求来源。 */
public final class SeckillAcceptanceDimensions {
  /** 纯字段检查工具不需要实例。 */
  private SeckillAcceptanceDimensions() {}

  /**
   * 检查原活动、用户与幂等键基本维度，不修改Map或读取外部资源。
   *
   * @param acceptance 原受理字段，允许缺失以表达缺证
   * @return 基本维度完整且类型、范围符合既有契约
   */
  public static boolean hasValidContext(Map<String, Object> acceptance) {
    // 1. 身份必须保留原整数类型与long精度，不从文本或浮点推断身份。
    if (acceptance == null
        || !isPositiveLong(acceptance.get("activity_id"))
        || !isPositiveLong(acceptance.get("user_id"))) return false;
    // 2. 原幂等键保字节内容与code point边界，不trim或大小写转换。
    Object originalKey = acceptance.get("idempotency_key");
    return originalKey instanceof String idempotencyKey
        && !"null".equals(idempotencyKey)
        && !idempotencyKey.isBlank()
        && idempotencyKey.codePointCount(0, idempotencyKey.length()) <= 128;
  }

  /** JDBC整数与整数DECIMAL只在精确转long且为正时有效；小数/溢出/浮点缺证返回false。 */
  private static boolean isPositiveLong(Object value) {
    if (!(value instanceof Number) || value instanceof Float || value instanceof Double)
      return false;
    try {
      BigDecimal exactValue =
          value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
      return exactValue.longValueExact() > 0;
    } catch (IllegalArgumentException | ArithmeticException invalidDimension) {
      return false;
    }
  }
}
