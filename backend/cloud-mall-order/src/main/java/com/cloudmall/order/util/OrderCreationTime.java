package com.cloudmall.order.util;

import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** 在生成月表归属前统一创建时刻，避免数据库舍入跨月或直接丢弃事件 offset。 */
public final class OrderCreationTime {
  /** 项目冻结的上海业务时间偏移。 */
  private static final ZoneOffset BUSINESS_OFFSET = ZoneOffset.ofHours(8);

  private OrderCreationTime() {}

  /**
   * 将同一瞬时转为上海偏移并向下截到毫秒，供订单及伴随创建记录共用。
   *
   * @param timestamp 已采样或已解析的创建时间
   * @return DATETIME(3) 可直接保存的上海毫秒时刻，不读取时钟或生成订单号
   * @throws IllegalArgumentException 时间为空、转换越界或年份不在1000..9999
   */
  public static OffsetDateTime normalize(OffsetDateTime timestamp) {
    // 1. 保留瞬时语义转换 offset，再消除数据库不支持的亚毫秒精度。
    if (timestamp == null) throw invalid();
    try {
      OffsetDateTime normalized =
          timestamp.withOffsetSameInstant(BUSINESS_OFFSET).truncatedTo(ChronoUnit.MILLIS);
      // 2. 只接受数据库可以表示的年份，不使用超界时间推导月表。
      if (normalized.getYear() < 1000 || normalized.getYear() > 9999) throw invalid();
      return normalized;
    } catch (DateTimeException invalidTime) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("ORDER_CREATION_TIME_INVALID");
  }
}
