package com.cloudmall.pay.config;

import io.seata.rm.datasource.DataSourceProxy;
import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;

/** Pay TCC bridge 的显式启用校验，不承担资源适配器注册。 */
@Component
public class PayTccBridgeConfiguration {
  /** 是否开启 Pay 到 User 的 TCC 派发桥。 */
  @Value("${cloudmall.pay.tcc-bridge.enabled:false}")
  private boolean enabled;

  /** Seata 客户端是否启用。 */
  @Value("${seata.enabled:false}")
  private boolean seataEnabled;

  /** 是否关闭 AT 自动数据源代理。 */
  @Value("${seata.enable-auto-data-source-proxy:false}")
  private boolean autoDataSourceProxy;

  /** User/Pay 本地事务管理器提供器。 */
  @Autowired private ObjectProvider<DataSourceTransactionManager> transactionManagerProvider;

  /** 应由同一事务管理器使用的本地数据源。 */
  @Autowired private ObjectProvider<DataSource> dataSourceProvider;

  /** 校验桥启用时的基础事务配置。 */
  @PostConstruct
  public void validate() {
    if (enabled && (!seataEnabled || autoDataSourceProxy)) {
      throw new IllegalStateException(
          "Pay TCC bridge requires seata.enabled=true and enable-auto-data-source-proxy=false");
    }
    if (enabled) {
      DataSourceTransactionManager transactionManager = transactionManagerProvider.getIfAvailable();
      DataSource dataSource = dataSourceProvider.getIfAvailable();
      if (transactionManager == null
          || dataSource == null
          || transactionManager.getDataSource() != dataSource
          || transactionManager.getDataSource() instanceof DataSourceProxy) {
        throw new IllegalStateException(
            "Pay TCC bridge requires the actual unproxied DataSourceTransactionManager data"
                + " source");
      }
    }
  }

  /** 返回桥是否启用。 */
  public boolean isEnabled() {
    return enabled;
  }
}
