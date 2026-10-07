package com.cloudmall.order.config;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.shardingsphere.api.config.sharding.ShardingRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.TableRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.strategy.StandardShardingStrategyConfiguration;

/** 从显式观测的月表清单生成隔离规则快照，不注册 Spring Bean 或创建数据源。 */
public final class OrderShardingRuleFactory {
  /** 固定的逻辑表顺序，同时作为输入 key 白名单。 */
  private static final List<String> TABLES =
      List.of("mall_order", "mall_order_item", "order_status_log");

  private OrderShardingRuleFactory() {}

  /**
   * 校验三表完整连续的月份清单，创建独立 Sharding-JDBC 4.1.1 规则快照。
   *
   * @param currentMonth 调用方按上海业务时间确定的当前月份
   * @param physicalTables 三类逻辑表各自的已观测物理表名，不包含 ds0 前缀
   * @return 新建规则对象；后续清单变化须显式重新构建，不热改已创建的数据源
   * @throws IllegalArgumentException 清单、月份或滚动窗口不完整或非法
   */
  public static ShardingRuleConfiguration create(
      YearMonth currentMonth, Map<String, ? extends Collection<String>> physicalTables) {
    // 1. 在生成任何规则前验证当前月和三个清单；不访问数据库或隐式补表。
    if (currentMonth == null
        || currentMonth.getYear() < 1000
        || currentMonth.getYear() > 9999
        || currentMonth.minusMonths(1).getYear() < 1000
        || currentMonth.plusMonths(2).getYear() > 9999) {
      throw invalid("current month or rolling window outside DATETIME range");
    }
    if (physicalTables == null || !physicalTables.keySet().equals(Set.copyOf(TABLES))) {
      throw invalid("exactly three order logical tables required");
    }
    SortedSet<String> months = null;
    for (String table : TABLES) {
      SortedSet<String> observed = months(table, physicalTables.get(table));
      if (months != null && !months.equals(observed)) throw invalid("companion month sets differ");
      months = observed;
    }

    // 2. 连续历史和滚动建表窗口都必须完整，避免只保留当前四个月丢失历史。
    if (ChronoUnit.MONTHS.between(parseMonth(months.first()), parseMonth(months.last())) + 1
        != months.size()) {
      throw invalid("historical month gap");
    }
    if (!months.containsAll(OrderTableInitializer.rollingMonths(currentMonth))) {
      throw invalid("rolling window incomplete");
    }

    // 3. 为每次调用构建新配置；字符串节点与调用方集合隔离，算法不持有输入清单。
    ShardingRuleConfiguration configuration = new ShardingRuleConfiguration();
    for (String table : TABLES) {
      String nodes =
          months.stream()
              .map(month -> "ds0." + table + "_" + month)
              .collect(Collectors.joining(","));
      TableRuleConfiguration rule = new TableRuleConfiguration(table, nodes);
      MonthlyOrderShardingAlgorithm algorithm = new MonthlyOrderShardingAlgorithm();
      rule.setTableShardingStrategyConfig(
          new StandardShardingStrategyConfiguration("created_at", algorithm, algorithm));
      configuration.getTableRuleConfigs().add(rule);
    }
    return configuration;
  }

  /** 只接受当前逻辑表的真实月表名，拒绝模板、混表、重复与无效后缀。 */
  private static SortedSet<String> months(String table, Collection<String> physicalTables) {
    if (physicalTables == null || physicalTables.isEmpty())
      throw invalid("empty physical table list");
    String prefix = table + "_";
    SortedSet<String> result = new TreeSet<>();
    for (String physicalTable : physicalTables) {
      if (physicalTable == null || !physicalTable.matches(prefix + "[0-9]{6}")) {
        throw invalid("physical table must match logical_table_yyyyMM");
      }
      String suffix = physicalTable.substring(prefix.length());
      parseMonth(suffix);
      if (!result.add(suffix)) throw invalid("duplicate physical month");
    }
    return result;
  }

  /** 固定六位年月已由调用方校验，数据库年份范围与17B时间算法一致。 */
  private static YearMonth parseMonth(String suffix) {
    try {
      YearMonth month =
          YearMonth.of(
              Integer.parseInt(suffix.substring(0, 4)), Integer.parseInt(suffix.substring(4)));
      if (month.getYear() < 1000) throw invalid("target month outside DATETIME range");
      return month;
    } catch (DateTimeException exception) {
      throw invalid("invalid physical month");
    }
  }

  private static IllegalArgumentException invalid(String reason) {
    return new IllegalArgumentException("ORDER_SHARD_INVENTORY_INVALID: " + reason);
  }
}
