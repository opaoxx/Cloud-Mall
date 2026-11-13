package com.cloudmall.pay.config;

import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.impl.receipt.PayTccReceiptBusinessCallback;
import com.cloudmall.pay.service.impl.receipt.PayTccReceiptLocalResourceImpl;
import com.cloudmall.pay.service.impl.receipt.PayTccReceiptStore;
import io.seata.rm.datasource.DataSourceProxy;
import io.seata.rm.tcc.config.TCCFenceConfig;
import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Pay receipt LocalTCC、Fence 和同数据源事务配置。 */
@Configuration
@ConditionalOnProperty(name = "cloudmall.pay.tcc-receipt.enabled", havingValue = "true")
public class PayTccReceiptConfiguration {
  /** 官方 TC/RM 客户端启用标志。 */
  @Value("${seata.enabled:false}")
  private boolean seataEnabled;

  /** TCC 本地事实不得进入 AT undo 代理。 */
  @Value("${seata.enable-auto-data-source-proxy:false}")
  private boolean autoDataSourceProxy;

  /** 持久派发锚点必须启用。 */
  private final PayTccBridgeConfiguration bridgeConfiguration;

  /** 用于 Fence 与业务的本地事务管理器。 */
  private final ObjectProvider<DataSourceTransactionManager> transactionManagers;

  /** 资金表和 Fence 共用的裸数据源。 */
  private final ObjectProvider<DataSource> dataSources;

  /** 创建 receipt 配置校验器。 */
  public PayTccReceiptConfiguration(
      PayTccBridgeConfiguration bridgeConfiguration,
      ObjectProvider<DataSourceTransactionManager> transactionManagers,
      ObjectProvider<DataSource> dataSources) {
    this.bridgeConfiguration = bridgeConfiguration;
    this.transactionManagers = transactionManagers;
    this.dataSources = dataSources;
  }

  /** 校验 receipt 与 bridge 使用同一未代理事务数据源。 */
  @PostConstruct
  public void validate() {
    DataSourceTransactionManager manager = transactionManagers.getIfAvailable();
    DataSource dataSource = dataSources.getIfAvailable();
    if (!seataEnabled
        || autoDataSourceProxy
        || !bridgeConfiguration.isEnabled()
        || manager == null
        || dataSource == null
        || manager.getDataSource() != dataSource
        || manager.getDataSource() instanceof DataSourceProxy) {
      throw new IllegalStateException(
          "Pay TCC receipt requires enabled bridge and one unproxied local transaction data"
              + " source");
    }
  }

  /** 创建 Pay receipt 业务 callback。 */
  @Bean
  public PayTccReceiptBusinessCallback payTccReceiptBusinessCallback(PayTccReceiptStore receipts) {
    return new PayTccReceiptBusinessCallback(receipts);
  }

  /** 初始化官方 Fence 存储及终态日志清理，不使用自动 Fence 拦截器。 */
  @Bean(initMethod = "initCleanTask", destroyMethod = "destroy")
  @DependsOn("payTccReceiptTableInitializer")
  public TCCFenceConfig tccFenceConfig(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      PayTccFenceMapper fences) {
    validate();
    fences.ensureTable();
    TCCFenceConfig configuration = new TCCFenceConfig(dataSource, transactionManager);
    configuration.getInitialized().set(true);
    return configuration;
  }

  /** 创建显式 Fence LocalTCC 资源。 */
  @Bean
  @DependsOn("tccFenceConfig")
  public PayTccReceiptLocalResource payTccReceiptLocalResource(
      PlatformTransactionManager transactionManager,
      PayTccReceiptBusinessCallback callback,
      PayTccFenceMapper fences,
      PayTccReceiptMapper receipts) {
    return new PayTccReceiptLocalResourceImpl(transactionManager, callback, fences, receipts);
  }
}
