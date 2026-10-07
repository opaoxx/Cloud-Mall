package com.cloudmall.order.util;

import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** 在生成月表归属前统一创建时刻，避免数据库舍入跨月或直接丢弃事件 offset。 */
public final class OrderCreationTime {
  /** 项目冻结的上海业务时间偏移。 */
  private static final ZoneOffset BUSINESS_OFFSET = ZoneOffset.ofHours(8);

  /** 原订单号固定使用的六位年月前缀。 */
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

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

  /**
   * 核验首次秒杀落单或恢复重放的显式时间与原订单月份，不猜测缺失时间。
   *
   * @param orderNo 原始订单号；该方法不重建订单号或选择物理表
   * @param occurredAt 事件中显式的 ISO-8601 字符串
   * @return 按17F规则规范化且与原号月份一致的上海毫秒时间
   * @throws IllegalArgumentException 时间或月份无法核验，标识为 ORDER_SECKILL_TIME_UNVERIFIED
   */
  public static OffsetDateTime forSeckill(String orderNo, Object occurredAt) {
    // 1. 只接受明确时间字符串和原有订单号格式，禁止用 now 或任意对象字符串补值。
    if (orderNo == null
        || !orderNo.matches("[0-9]{6}[A-Za-z0-9-]+")
        || !(occurredAt instanceof String timestamp)
        || timestamp.isBlank()) {
      throw unverified();
    }
    OffsetDateTime normalized;
    try {
      normalized = normalize(OffsetDateTime.parse(timestamp));
    } catch (DateTimeException | IllegalArgumentException invalidTimestamp) {
      throw unverified();
    }
    // 2. 在上海时间和毫秒语义下核对月份；非法月份也不能等于规范化结果。
    if (!normalized.format(MONTH).equals(orderNo.substring(0, 6))) throw unverified();
    return normalized;
  }

  private static IllegalArgumentException unverified() {
    return new IllegalArgumentException("ORDER_SECKILL_TIME_UNVERIFIED");
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("ORDER_CREATION_TIME_INVALID");
  }
}
