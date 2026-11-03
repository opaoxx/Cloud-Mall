package com.cloudmall.stock.config;

import com.cloudmall.stock.mapper.StockSalesOutboxMapper;
import javax.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Sender-only Stock configuration; it does not redeclare Product-owned queue arguments. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cloudmall.stock.sales-metrics.enabled", havingValue = "true")
public class StockSalesMetricsConfiguration {
  /** Same Stock mapper resource as the confirmation transaction. */
  private final StockSalesOutboxMapper mapper;

  /** Original Stock local manager. */
  private final PlatformTransactionManager manager;

  /** Final interval configuration. */
  private final Environment environment;

  /** Construct enabled-only schema and transaction admission. */
  public StockSalesMetricsConfiguration(
      StockSalesOutboxMapper mapper, PlatformTransactionManager manager, Environment environment) {
    this.mapper = mapper;
    this.manager = manager;
    this.environment = environment;
  }

  /** Reject incompatible schema/resources before any confirmation may report success. */
  @PostConstruct
  public void validate() {
    if (!(manager instanceof DataSourceTransactionManager local)
        || local.getDataSource() != mapper.dataSource())
      throw new IllegalStateException("SALES_TRANSACTION_RESOURCE_MISMATCH");
    long delay =
        environment.getProperty(
            "cloudmall.stock.sales-metrics.dispatch-interval-ms", Long.class, 1000L);
    if (delay < 100 || delay > 60000) throw new IllegalStateException("SALES_INTERVAL_INVALID");
    mapper.verifySchema();
  }
}
