package com.cloudmall.order.mapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Physical-only, read-only inventory and row admission, never a business Spring repository. */
public final class OrderShardingInventoryMapper {
  /** Private physical JDBC; bounded query timeout is configured by its bootstrap owner. */
  private final JdbcTemplate jdbc;

  /** Exact catalog observed on the maintenance connection. */
  private final String catalog;

  /** Only these three logical families may contribute monthly routing nodes. */
  private static final List<String> FAMILIES =
      List.of("mall_order", "mall_order_item", "order_status_log");

  /** Verifies the actual selected catalog and Shanghai SQL wall clock before maintenance DDL. */
  public OrderShardingInventoryMapper(JdbcTemplate jdbc, String expectedCatalog) {
    this.jdbc = jdbc;
    this.catalog = jdbc.queryForObject("SELECT DATABASE()", String.class);
    if (catalog == null || !catalog.equals(expectedCatalog) || !catalog.matches("[A-Za-z0-9_]+"))
      throw failure("ORDER_SHARD_CATALOG_INVALID");
    Integer offset =
        jdbc.queryForObject("SELECT TIMESTAMPDIFF(MINUTE,UTC_TIMESTAMP(),NOW())", Integer.class);
    if (offset == null || offset != 480) throw failure("ORDER_SHARD_SESSION_TIME_INVALID");
    for (String foundation :
        List.of(
            "mall_order_template",
            "mall_order_item_template",
            "order_status_log_template",
            "order_idempotency")) {
      List<String> types =
          jdbc.queryForList(
              "SELECT TABLE_TYPE FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND"
                  + " TABLE_NAME=?",
              String.class,
              foundation);
      if (!types.equals(List.of("BASE TABLE"))) throw failure("ORDER_SHARD_FOUNDATION_UNAVAILABLE");
    }
  }

  /** Returns real BASE TABLE names grouped by family; invalid aliases and monthly views fail. */
  public Map<String, List<String>> observe() {
    // 1. Read all actual objects, not a calculated expected-month list.
    List<Map<String, Object>> objects =
        jdbc.queryForList(
            "SELECT TABLE_NAME,TABLE_TYPE FROM information_schema.tables WHERE"
                + " TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME");
    Map<String, List<String>> inventory = new LinkedHashMap<>();
    FAMILIES.forEach(family -> inventory.put(family, new ArrayList<>()));
    // 2. Match the longest family first so item names are never treated as main names.
    for (Map<String, Object> object : objects) {
      String name = String.valueOf(object.get("TABLE_NAME"));
      for (String family : List.of("mall_order_item", "order_status_log", "mall_order")) {
        if (!name.startsWith(family + "_")) continue;
        if (!name.equals(family + "_template")) {
          if (!name.matches(family + "_[1-9][0-9]{3}(0[1-9]|1[0-2])")
              || !"BASE TABLE".equals(object.get("TABLE_TYPE")))
            throw failure("ORDER_SHARD_OBJECT_INVALID");
          inventory.get(family).add(name);
        }
        break;
      }
    }
    // 3. Isolate all returned lists from mutable metadata accumulation.
    Map<String, List<String>> snapshot = new LinkedHashMap<>();
    inventory.forEach((family, tables) -> snapshot.put(family, List.copyOf(tables)));
    return java.util.Collections.unmodifiableMap(snapshot);
  }

  /**
   * Rejects original numbers/created_at whose local month differs from the actual main/item table.
   */
  public void admit(Map<String, List<String>> inventory) {
    for (String family : List.of("mall_order", "mall_order_item")) {
      for (String table : inventory.get(family)) {
        if (!table.matches(family + "_[1-9][0-9]{3}(0[1-9]|1[0-2])"))
          throw failure("ORDER_SHARD_OBJECT_INVALID");
        String suffix = table.substring(family.length() + 1);
        Long invalid =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM "
                    + table
                    + " WHERE order_no IS NULL OR created_at IS NULL OR NOT"
                    + " REGEXP_LIKE(order_no,'^[0-9]{6}[A-Za-z0-9-]+$','c') OR BINARY"
                    + " SUBSTRING(order_no,1,6)<>BINARY ? OR DATE_FORMAT(created_at,'%Y%m')<>?",
                Long.class,
                suffix,
                suffix);
        if (invalid == null || invalid != 0) throw failure("ORDER_SHARD_ROW_ADMISSION_FAILED");
      }
    }
  }

  /** Returns only the verified catalog identity to the owning bootstrap. */
  public String catalog() {
    return catalog;
  }

  private static DataAccessResourceFailureException failure(String category) {
    return new DataAccessResourceFailureException(category);
  }
}
