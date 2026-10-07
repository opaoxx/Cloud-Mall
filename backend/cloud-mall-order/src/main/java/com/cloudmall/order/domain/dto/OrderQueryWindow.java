package com.cloudmall.order.domain.dto;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;

/** 保持半开区间语义的订单 DATETIME(3) 查询窗口，不读取时钟或数据库。 */
public final class OrderQueryWindow {
  /** 项目冻结的上海本地时间偏移。 */
  private static final ZoneOffset BUSINESS_OFFSET = ZoneOffset.ofHours(8);

  /** DATETIME(3) 的毫秒刻度，以纳秒表示。 */
  private static final int NANOS_PER_MILLISECOND = 1_000_000;

  /** 已向上对齐毫秒的上海时间包含下界。 */
  private final OffsetDateTime start;

  /** 已向上对齐毫秒的上海时间排除上界。 */
  private final OffsetDateTime end;

  private OrderQueryWindow(OffsetDateTime start, OffsetDateTime end) {
    this.start = start;
    this.end = end;
  }

  /**
   * 解析同一次时钟采样下的请求区间，并将两端向上对齐至可存储毫秒。
   *
   * @param startTime 可空的 ISO-8601 包含下界，缺省为当前时间前一个月
   * @param endTime 可空的 ISO-8601 排除上界，缺省为当前时间后1秒
   * @param currentTime 调用方一次采样的当前时间
   * @return 无副作用的规范化窗口；空窗口不应执行 SQL
   * @throws BizException 时间无效、原始区间非递增或端点超出 DATETIME 范围时返回400
   */
  public static OrderQueryWindow from(
      String startTime, String endTime, OffsetDateTime currentTime) {
    // 1. 缺省值共用同一时钟采样，所有端点都按同一瞬时转为上海偏移。
    try {
      if (currentTime == null) throw invalid();
      OffsetDateTime current = currentTime.withOffsetSameInstant(BUSINESS_OFFSET);
      OffsetDateTime rawStart =
          startTime == null
              ? current.minusMonths(1)
              : OffsetDateTime.parse(startTime).withOffsetSameInstant(BUSINESS_OFFSET);
      OffsetDateTime rawEnd =
          endTime == null
              ? current.plusSeconds(1)
              : OffsetDateTime.parse(endTime).withOffsetSameInstant(BUSINESS_OFFSET);
      requireDatabaseYear(rawStart);
      requireDatabaseYear(rawEnd);
      if (!rawStart.isBefore(rawEnd)) throw invalid();

      // 2. 对离散毫秒值，x>=start与x<end分别等价于x>=ceil(start)、x<ceil(end)。
      OffsetDateTime normalizedStart = ceilMillisecond(rawStart);
      OffsetDateTime normalizedEnd = ceilMillisecond(rawEnd);
      requireDatabaseYear(normalizedStart);
      requireDatabaseYear(normalizedEnd);
      if (normalizedStart.isAfter(normalizedEnd)) throw invalid();
      return new OrderQueryWindow(normalizedStart, normalizedEnd);
    } catch (DateTimeException invalidTime) {
      throw invalid();
    }
  }

  /** 返回规范化包含下界，供 JDBC 参数转换和未来隔离路由共用。 */
  public OffsetDateTime getStart() {
    return start;
  }

  /** 返回规范化排除上界。 */
  public OffsetDateTime getEnd() {
    return end;
  }

  /** 返回区间是否不含任何可存储毫秒值。 */
  public boolean isEmpty() {
    return start.equals(end);
  }

  /** 返回非空窗口的首月；空窗口禁止计算查询月份。 */
  public YearMonth getFirstMonth() {
    requireNonEmpty();
    return YearMonth.from(start);
  }

  /** 返回非空窗口最后一个可存储毫秒所在月份。 */
  public YearMonth getLastMonth() {
    requireNonEmpty();
    return YearMonth.from(end.minusNanos(NANOS_PER_MILLISECOND));
  }

  private void requireNonEmpty() {
    if (isEmpty()) throw new IllegalStateException("empty order window has no query months");
  }

  private static OffsetDateTime ceilMillisecond(OffsetDateTime value) {
    int remainder = value.getNano() % NANOS_PER_MILLISECOND;
    return remainder == 0 ? value : value.plusNanos(NANOS_PER_MILLISECOND - remainder);
  }

  private static void requireDatabaseYear(OffsetDateTime value) {
    if (value.getYear() < 1000 || value.getYear() > 9999) throw invalid();
  }

  private static BizException invalid() {
    return new BizException(ErrorCodes.INVALID, "时间范围无效", 400);
  }
}
