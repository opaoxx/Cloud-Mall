package com.cloudmall.product.util;

import java.time.OffsetDateTime;

/** 秒杀活动剩余库存的原保守解释规则。 */
public final class SeckillActivityStock {
  /** 禁止实例化库存解释工具。 */
  private SeckillActivityStock() {}

  /** 过期或无法确认的库存返回零，保留原边界与异常语义。 */
  public static int remainingStock(
      String redisValue, String status, OffsetDateTime endAt, OffsetDateTime currentTime) {
    if (endAt == null
        || !endAt.isAfter(currentTime)
        || "ENDED".equals(status)
        || "CLOSED".equals(status)) return 0;
    if (redisValue == null) return 0;
    try {
      return Math.max(0, Integer.parseInt(redisValue));
    } catch (NumberFormatException invalidStock) {
      return 0;
    }
  }
}
