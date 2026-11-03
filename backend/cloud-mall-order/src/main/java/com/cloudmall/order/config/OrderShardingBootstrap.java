package com.cloudmall.order.config;

import com.cloudmall.order.mapper.*;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.apache.shardingsphere.shardingjdbc.api.ShardingDataSourceFactory;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.datasource.ShardingDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns private physical maintenance and one immutable published Sharding snapshot. */
public final class OrderShardingBootstrap implements AutoCloseable {
  /** Private bottom-level pool, never a Spring DataSource candidate. */
  private final HikariDataSource physical;

  /** Original physical DDL mapper, never injected into business code. */
  private final OrderSqlMapper maintenance;

  /** Original rolling DDL implementation using the private maintenance mapper. */
  private final OrderTableInitializer tableInitializer;

  /** Actual catalog and admission reader, not a Spring repository. */
  private final OrderShardingInventoryMapper inventory;

  /** Fixed business data source; rules never change after construction. */
  private final ShardingDataSource published;

  /** Fixed sorted month snapshot, copied from actual observed table names. */
  private final List<String> publishedMonths;

  /** Last successfully observed prepared months, never used to mutate routing rules. */
  private volatile List<String> preparedMonths;

  /** Digest of exactly the published family/node snapshot. */
  private final String snapshotSha256;

  /** Idempotent private-resource close guard. */
  private boolean closed;

  /** Runs original physical preparation, real admission and immutable snapshot creation. */
  OrderShardingBootstrap(
      DataSourceProperties properties, Environment environment, YearMonth currentMonth)
      throws SQLException {
    // 1. Bind the original Boot/Hikari settings to an unregistered private physical source.
    HikariDataSource raw =
        properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    ShardingDataSource created = null;
    try {
      Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(raw));
      raw.setPoolName("order-sharding-maintenance");
      raw.setConnectionTimeout(Math.min(raw.getConnectionTimeout(), 5000));
      raw.setValidationTimeout(Math.min(raw.getValidationTimeout(), 3000));
      raw.addDataSourceProperty("connectTimeout", "3000");
      raw.addDataSourceProperty("socketTimeout", "5000");
      raw.addDataSourceProperty("serverTimezone", "Asia/Shanghai");
      raw.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
      JdbcTemplate physicalJdbc = new JdbcTemplate(raw);
      physicalJdbc.setQueryTimeout(5);
      String url = properties.determineUrl();
      if (!url.startsWith("jdbc:mysql://"))
        throw new IllegalStateException("ORDER_SHARD_CATALOG_INVALID");
      String catalog = java.net.URI.create(url.substring(5)).getPath();
      if (catalog == null || !catalog.matches("/[A-Za-z0-9_]+"))
        throw new IllegalStateException("ORDER_SHARD_CATALOG_INVALID");
      inventory = new OrderShardingInventoryMapper(physicalJdbc, catalog.substring(1));
      maintenance = new OrderSqlMapper(physicalJdbc);
      tableInitializer = new OrderTableInitializer(maintenance);
      // 2. Reuse the exact original DDL algorithms and reliability initialization order.
      tableInitializer.prepareForMonth(currentMonth);
      new OrderReliabilityTableInitializer(
              new OrderReservationIntentMapper(physicalJdbc),
              new OrderOutboxMapper(physicalJdbc),
              new OrderStockActionMapper(physicalJdbc),
              new SeckillOrderDedupMapper(physicalJdbc),
              new SeckillMessageRecoveryMapper(physicalJdbc))
          .prepareTables();
      Map<String, List<String>> observed = inventory.observe();
      var rule = OrderShardingRuleFactory.create(currentMonth, observed);
      inventory.admit(observed);
      // 3. Freeze observed nodes and construct the only business Sharding source.
      publishedMonths = months(observed);
      preparedMonths = publishedMonths;
      snapshotSha256 = digest(observed);
      created =
          (ShardingDataSource)
              ShardingDataSourceFactory.createDataSource(
                  Map.of("ds0", raw), rule, new Properties());
      physical = raw;
      published = created;
    } catch (SQLException | RuntimeException | Error failure) {
      if (created != null)
        try {
          created.close();
        } catch (Throwable closeFailure) {
          failure.addSuppressed(closeFailure);
        }
      try {
        raw.close();
      } catch (Throwable closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  /**
   * Returns the dependent business bean; bootstrap retains a close fallback for failed publication.
   */
  public synchronized DataSource businessDataSource() {
    requireReady();
    return published;
  }

  /** Confirms exact published source and ds0 ownership, without opening a connection. */
  public synchronized void verifyBinding(DataSource selected) {
    requireReady();
    if (selected != published
        || !published.getDataSourceMap().keySet().equals(java.util.Set.of("ds0"))
        || published.getDataSourceMap().get("ds0") != physical)
      throw new IllegalStateException(OrderRoutingConfiguration.UNSUPPORTED);
  }

  /** Refuses new-mode lifecycle fallback when preparation did not complete or was closed. */
  public synchronized void requireReady() {
    if (closed || published == null || physical.isClosed())
      throw new IllegalStateException("ORDER_SHARD_BOOTSTRAP_NOT_READY");
  }

  /**
   * Prepares the original rolling window physically, then updates only observed prepared metadata.
   */
  synchronized void prepareForMonth(YearMonth currentMonth) {
    requireReady();
    tableInitializer.prepareForMonth(currentMonth);
    Map<String, List<String>> observed = inventory.observe();
    OrderShardingRuleFactory.create(currentMonth, observed);
    inventory.admit(observed);
    preparedMonths = months(observed);
  }

  /** Returns a safe memory-only snapshot, never connection settings or database identities. */
  public Map<String, Object> info() {
    return Map.of(
        "publishedMonths",
        publishedMonths,
        "preparedMonths",
        preparedMonths,
        "snapshotSha256",
        snapshotSha256,
        "requiresRestart",
        !publishedMonths.equals(preparedMonths));
  }

  /** Provides immutable published node months to the sole logical mapper. */
  public List<String> publishedMonths() {
    return publishedMonths;
  }

  /**
   * Independently closes built Sharding state, including a failed bean publication, and the raw
   * pool.
   */
  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    Throwable failure = null;
    try {
      published.close();
    } catch (Throwable closeFailure) {
      failure = closeFailure;
    }
    try {
      physical.close();
    } catch (Throwable closeFailure) {
      if (failure == null) failure = closeFailure;
      else failure.addSuppressed(closeFailure);
    }
    if (failure instanceof Error fatal) throw fatal;
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure != null) throw new IllegalStateException("ORDER_SHARD_CLOSE_FAILED", failure);
  }

  private static List<String> months(Map<String, List<String>> observed) {
    TreeSet<String> result = new TreeSet<>();
    observed
        .get("mall_order")
        .forEach(table -> result.add(table.substring("mall_order_".length())));
    return List.copyOf(result);
  }

  private static String digest(Map<String, List<String>> observed) {
    try {
      String canonical =
          observed.entrySet().stream()
              .sorted(Map.Entry.comparingByKey())
              .map(entry -> entry.getKey() + "=" + String.join(",", entry.getValue()))
              .collect(java.util.stream.Collectors.joining("\n"));
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
