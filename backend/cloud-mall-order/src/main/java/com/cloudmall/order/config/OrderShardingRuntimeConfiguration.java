package com.cloudmall.order.config;

import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.ShardingOrderSqlMapper;
import java.sql.SQLException;
import java.time.YearMonth;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Explicit opt-in Bean graph, sampled once per context and never hot swapped. */
@Configuration(proxyBeanMethods = false)
@Conditional(OrderRoutingConfiguration.ShardingMonthly.class)
@EnableConfigurationProperties(DataSourceProperties.class)
public class OrderShardingRuntimeConfiguration {
  /** Single sampled Shanghai month used only to build this immutable context. */
  private final YearMonth currentMonth;

  /** Samples the actual Shanghai system month for a normal production Spring context. */
  public OrderShardingRuntimeConfiguration() {
    this(YearMonth.now(ZoneId.of("Asia/Shanghai")));
  }

  /** Same production construction core used by the bounded package-local publication test. */
  OrderShardingRuntimeConfiguration(YearMonth currentMonth) {
    this.currentMonth = java.util.Objects.requireNonNull(currentMonth);
  }

  /** Owns private physical DDL and the observed immutable rule snapshot. */
  @Bean(destroyMethod = "close")
  public OrderShardingBootstrap orderShardingBootstrap(
      DataSourceProperties properties, Environment environment) throws SQLException {
    return new OrderShardingBootstrap(properties, environment, currentMonth);
  }

  /** Publishes the sole business source after physical preparation and admission. */
  @Bean(name = "dataSource", destroyMethod = "close")
  public DataSource dataSource(OrderShardingBootstrap bootstrap) {
    return bootstrap.businessDataSource();
  }

  /** Creates the unique business JDBC bound to that exact source. */
  @Bean
  public JdbcTemplate jdbcTemplate(DataSource dataSource) {
    return new JdbcTemplate(dataSource);
  }

  /** Preserves local transactions and existing REQUIRES_NEW on the same source. */
  @Bean
  public DataSourceTransactionManager transactionManager(DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  /** Selects the only business OrderSqlMapper for this mode. */
  @Bean
  public OrderSqlMapper orderSqlMapper(
      JdbcTemplate jdbcTemplate, OrderShardingBootstrap bootstrap) {
    return new ShardingOrderSqlMapper(jdbcTemplate, bootstrap.publishedMonths());
  }
}
