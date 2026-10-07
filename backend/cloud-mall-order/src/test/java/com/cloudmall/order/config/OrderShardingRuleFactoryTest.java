package com.cloudmall.order.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.shardingsphere.api.config.sharding.ShardingRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.TableRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.strategy.StandardShardingStrategyConfiguration;
import org.junit.jupiter.api.Test;

/** 验证跨年窗口、三表清单完整性和规则快照独立性。 */
class OrderShardingRuleFactoryTest {
  /** 包含一月历史和跨年的完整 December 观测窗口。 */
  private static final List<String> MONTHS =
      List.of("202610", "202611", "202612", "202701", "202702");

  @Test
  void generatesAllHistoricalAndRollingNodesWithTypedAlgorithms() {
    ShardingRuleConfiguration configuration =
        OrderShardingRuleFactory.create(YearMonth.of(2026, 12), inventory(MONTHS));
    assertEquals(3, configuration.getTableRuleConfigs().size());
    for (TableRuleConfiguration rule : configuration.getTableRuleConfigs()) {
      String table = rule.getLogicTable();
      assertEquals(
          "ds0."
              + table
              + "_202610,ds0."
              + table
              + "_202611,ds0."
              + table
              + "_202612,ds0."
              + table
              + "_202701,ds0."
              + table
              + "_202702",
          rule.getActualDataNodes());
      StandardShardingStrategyConfiguration strategy =
          assertInstanceOf(
              StandardShardingStrategyConfiguration.class, rule.getTableShardingStrategyConfig());
      assertEquals("created_at", strategy.getShardingColumn());
      assertInstanceOf(MonthlyOrderShardingAlgorithm.class, strategy.getPreciseShardingAlgorithm());
      assertSame(strategy.getPreciseShardingAlgorithm(), strategy.getRangeShardingAlgorithm());
    }
  }

  @Test
  void januaryRequiresMarchAndRebuildingDoesNotChangeExistingSnapshot() {
    Map<String, Collection<String>> tables = inventory(MONTHS);
    ShardingRuleConfiguration december =
        OrderShardingRuleFactory.create(YearMonth.of(2026, 12), tables);
    assertInvalid(() -> OrderShardingRuleFactory.create(YearMonth.of(2027, 1), tables));
    tables.forEach((table, names) -> names.add(table + "_202703"));
    ShardingRuleConfiguration january =
        OrderShardingRuleFactory.create(YearMonth.of(2027, 1), tables);
    assertTrue(
        december.getTableRuleConfigs().stream()
            .noneMatch(rule -> rule.getActualDataNodes().contains("202703")));
    assertTrue(
        january.getTableRuleConfigs().stream()
            .allMatch(rule -> rule.getActualDataNodes().endsWith("_202703")));
    tables.clear();
    december.getTableRuleConfigs().clear();
    assertEquals(3, january.getTableRuleConfigs().size());
  }

  @Test
  void rejectsMissingCompanionEvenForHistoricalMonths() {
    Map<String, Collection<String>> tables = inventory(MONTHS);
    tables.get("order_status_log").remove("order_status_log_202610");
    assertInvalid(() -> OrderShardingRuleFactory.create(YearMonth.of(2026, 12), tables));
  }

  @Test
  void rejectsCommonHistoricalGapAndIncompleteRollingWindow() {
    assertInvalid(
        () ->
            OrderShardingRuleFactory.create(
                YearMonth.of(2026, 12),
                inventory(List.of("202608", "202610", "202611", "202612", "202701", "202702"))));
    assertInvalid(
        () ->
            OrderShardingRuleFactory.create(
                YearMonth.of(2026, 12),
                inventory(List.of("202610", "202611", "202612", "202701"))));
  }

  @Test
  void rejectsForeignKeysNullAndEmptyInventories() {
    assertInvalid(() -> OrderShardingRuleFactory.create(YearMonth.of(2026, 12), null));
    assertInvalid(() -> OrderShardingRuleFactory.create(YearMonth.of(2026, 12), Map.of()));
    Map<String, Collection<String>> tables = inventory(MONTHS);
    tables.put("extra", List.of("extra_202612"));
    assertInvalid(() -> OrderShardingRuleFactory.create(YearMonth.of(2026, 12), tables));
    tables.remove("extra");
    tables.put("mall_order", null);
    assertInvalid(() -> OrderShardingRuleFactory.create(YearMonth.of(2026, 12), tables));
    tables.put("mall_order", List.of());
    assertInvalid(() -> OrderShardingRuleFactory.create(YearMonth.of(2026, 12), tables));
  }

  @Test
  void rejectsMalformedMixedDuplicateAndOutOfRangeNames() {
    for (String invalid :
        new String[] {
          null,
          "mall_order_202610",
          "mall_order_202613",
          "mall_order_099912",
          "mall_order_item_202612",
          "ds0.mall_order_202612",
          "mall_order_template",
          "mall_order_20261x"
        }) {
      Map<String, Collection<String>> tables = inventory(MONTHS);
      tables.get("mall_order").add(invalid);
      assertInvalid(() -> OrderShardingRuleFactory.create(YearMonth.of(2026, 12), tables));
    }
  }

  @Test
  void rejectsInvalidCurrentMonthAndRollingWindowExtremes() {
    for (YearMonth current :
        new YearMonth[] {
          null,
          YearMonth.of(999, 12),
          YearMonth.of(10000, 1),
          YearMonth.of(1000, 1),
          YearMonth.of(9999, 11),
          YearMonth.of(9999, 12)
        }) {
      assertInvalid(() -> OrderShardingRuleFactory.create(current, inventory(MONTHS)));
    }
    assertNotNull(
        OrderShardingRuleFactory.create(
            YearMonth.of(1000, 2), inventory(List.of("100001", "100002", "100003", "100004"))));
    assertNotNull(
        OrderShardingRuleFactory.create(
            YearMonth.of(9999, 10), inventory(List.of("999909", "999910", "999911", "999912"))));
  }

  private Map<String, Collection<String>> inventory(List<String> months) {
    Map<String, Collection<String>> result = new LinkedHashMap<>();
    for (String table : List.of("mall_order", "mall_order_item", "order_status_log")) {
      List<String> names = new ArrayList<>();
      for (String month : months) names.add(table + "_" + month);
      java.util.Collections.reverse(names);
      result.put(table, names);
    }
    return result;
  }

  private void assertInvalid(org.junit.jupiter.api.function.Executable action) {
    assertTrue(
        assertThrows(IllegalArgumentException.class, action)
            .getMessage()
            .startsWith("ORDER_SHARD_INVENTORY_INVALID:"));
  }
}
