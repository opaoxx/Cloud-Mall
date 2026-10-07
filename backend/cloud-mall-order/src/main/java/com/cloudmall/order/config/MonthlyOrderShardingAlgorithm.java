package com.cloudmall.order.config;

import com.google.common.collect.BoundType;
import com.google.common.collect.Range;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.shardingsphere.api.sharding.standard.PreciseShardingAlgorithm;
import org.apache.shardingsphere.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.api.sharding.standard.RangeShardingAlgorithm;
import org.apache.shardingsphere.api.sharding.standard.RangeShardingValue;

/** Routes all order-domain tables by the DATETIME(3) created_at month. */
public final class MonthlyOrderShardingAlgorithm
    implements PreciseShardingAlgorithm<Comparable<?>>, RangeShardingAlgorithm<Comparable<?>> {
  /** 已冻结的三类订单逻辑表；不允许后缀匹配路由到其他表。 */
  private static final List<String> LOGICAL_TABLES =
      List.of("mall_order", "mall_order_item", "order_status_log");

  /** DATETIME(3) 的一个离散时间刻度，单位为纳秒。 */
  private static final int NANOS_PER_MILLISECOND = 1_000_000;

  /**
   * 按已物化的上海本地时间选择唯一物理月表。
   *
   * @param targets 当前逻辑表的物理表名，不包含数据源前缀
   * @param value created_at 的 JDBC 时间值
   * @return 唯一且存在于目标集合的物理表名
   * @throws IllegalArgumentException 输入、目标或所需月份不满足冻结契约
   */
  @Override
  public String doSharding(Collection<String> targets, PreciseShardingValue<Comparable<?>> value) {
    // 1. 每次只校验当前逻辑表，不将配置目标视为物理表存在的证明。
    if (value == null) throw invalid("missing precise value");
    Map<YearMonth, String> months =
        targets(targets, value.getLogicTableName(), value.getColumnName());
    // 2. 禁止字符串猜测或时区二次转换，缺月时明确失败。
    return requireMonth(months, YearMonth.from(localTime(value.getValue())));
  }

  /**
   * 按毫秒离散值计算有界查询覆盖的全部月份；缺月时整体失败。
   *
   * @param targets 当前逻辑表的物理表名
   * @param value created_at 的有界时间区间
   * @return 按月份升序排列的物理表名
   * @throws IllegalArgumentException 无界查询、非法输入或所需月份未配置；空区间为 ORDER_SHARD_ROUTE_EMPTY
   */
  @Override
  public Collection<String> doSharding(
      Collection<String> targets, RangeShardingValue<Comparable<?>> value) {
    // 1. 拒绝无界广播，并校验目标和两端时间的类型、精度。
    if (value == null) throw invalid("missing range value");
    Map<YearMonth, String> months =
        targets(targets, value.getLogicTableName(), value.getColumnName());
    Range<Comparable<?>> range = value.getValueRange();
    if (range == null || !range.hasLowerBound() || !range.hasUpperBound()) {
      throw invalid("bounded created_at range required");
    }
    LocalDateTime lower = localTime(range.lowerEndpoint());
    LocalDateTime upper = localTime(range.upperEndpoint());

    // 2. 开边界移到下一个/上一个可存储毫秒，避免月末开下界多路由一张表。
    if (range.lowerBoundType() == BoundType.OPEN) lower = lower.plusNanos(NANOS_PER_MILLISECOND);
    if (range.upperBoundType() == BoundType.OPEN) upper = upper.minusNanos(NANOS_PER_MILLISECOND);
    if (lower.isAfter(upper)) {
      // Sharding-JDBC 4.1.1 不接受空目标集合；以稳定标记交给未来查询适配层处理。
      throw new IllegalArgumentException(
          "ORDER_SHARD_ROUTE_EMPTY: no representable DATETIME(3) value");
    }
    YearMonth first = YearMonth.from(lower);
    YearMonth last = YearMonth.from(upper);

    // 3. 先限制枚举长度，再逐月验证；内部缺口不能产生部分路由结果。
    long requiredCount = ChronoUnit.MONTHS.between(first, last) + 1;
    if (requiredCount > months.size()) throw invalid("requested months unavailable");
    List<String> result = new ArrayList<>();
    for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
      result.add(requireMonth(months, month));
    }
    return result;
  }

  /** 校验单表目标白名单和年月格式，拒绝混表、重复与无效月份。 */
  private Map<YearMonth, String> targets(
      Collection<String> available, String logicalTable, String column) {
    if (logicalTable == null
        || !LOGICAL_TABLES.contains(logicalTable)
        || !"created_at".equals(column)) {
      throw invalid("unsupported logical table or sharding column");
    }
    if (available == null || available.isEmpty()) throw invalid("empty targets");
    String prefix = logicalTable + "_";
    Map<YearMonth, String> result = new TreeMap<>();
    for (String target : available) {
      if (target == null || !target.matches(prefix + "[0-9]{6}")) {
        throw invalid("target must match logical_table_yyyyMM");
      }
      String suffix = target.substring(prefix.length());
      YearMonth month;
      try {
        month =
            YearMonth.of(
                Integer.parseInt(suffix.substring(0, 4)), Integer.parseInt(suffix.substring(4)));
      } catch (DateTimeException exception) {
        throw invalid("invalid target month");
      }
      if (month.getYear() < 1000 || result.putIfAbsent(month, target) != null) {
        throw invalid("invalid or duplicate target month");
      }
    }
    return result;
  }

  /** Timestamp 沿用 JDBC 已物化的本地字段；不从 Instant 或字符串推测时区。 */
  private LocalDateTime localTime(Object value) {
    LocalDateTime local;
    if (value instanceof LocalDateTime) local = (LocalDateTime) value;
    else if (value instanceof Timestamp) local = ((Timestamp) value).toLocalDateTime();
    else throw invalid("LocalDateTime or Timestamp required");
    if (local.getYear() < 1000
        || local.getYear() > 9999
        || local.getNano() % NANOS_PER_MILLISECOND != 0) {
      throw invalid("created_at must be a DATETIME(3) value");
    }
    return local;
  }

  private String requireMonth(Map<YearMonth, String> targets, YearMonth month) {
    String target = targets.get(month);
    if (target == null) throw invalid("requested month unavailable: " + month);
    return target;
  }

  private IllegalArgumentException invalid(String reason) {
    return new IllegalArgumentException("ORDER_SHARD_ROUTE_INVALID: " + reason);
  }
}
