package com.cloudmall.user.config;

import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceTccResourceEvidenceMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.BalanceTccDebitService;
import com.cloudmall.user.service.impl.BalanceTccDebitBusinessCallback;
import com.cloudmall.user.service.impl.BalanceTccDebitLocalResourceImpl;
import io.seata.rm.datasource.DataSourceProxy;
import io.seata.rm.tcc.config.TCCFenceConfig;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** 显式开启 User RM 时才绑定 Fence；默认不注册新资金资源。 */
@Configuration
@ConditionalOnProperty(name = "cloudmall.user.tcc-resource.enabled", havingValue = "true")
public class BalanceTccResourceConfiguration {
  /** 官方 Fence 和业务必须使用相同裸数据源、相同本地事务管理器。 */
  @Bean(initMethod = "initCleanTask", destroyMethod = "destroy")
  @DependsOn({
    "userTableInitializer",
    "balanceTccTableInitializer",
    "balanceLedgerTableInitializer"
  })
  public TCCFenceConfig tccFenceConfig(
      DataSource dataSource,
      PlatformTransactionManager transactionManager,
      TccFenceLogMapper mapper,
      Environment environment) {
    if (!environment.getProperty("seata.enabled", Boolean.class, false)
        || environment.getProperty("seata.enable-auto-data-source-proxy", Boolean.class, true)
        || dataSource instanceof DataSourceProxy
        || !(transactionManager instanceof DataSourceTransactionManager)
        || ((DataSourceTransactionManager) transactionManager).getDataSource() != dataSource)
      throw new IllegalStateException("User TCC 要求启用 Seata、关闭 AT 代理并共用 User 本地事务");
    mapper.ensureTable();
    TCCFenceConfig fenceConfig = new TCCFenceConfig(dataSource, transactionManager);
    // 显式 Fence 不走自动拦截器的清理初始化，使用官方终态日志清理生命周期。
    fenceConfig.getInitialized().set(true);
    return fenceConfig;
  }

  /** 创建无 HTTP 映射的资金业务委托。 */
  @Bean
  public BalanceTccDebitBusinessCallback balanceTccDebitBusinessCallback(
      BalanceTccDebitService funds) {
    return new BalanceTccDebitBusinessCallback(funds);
  }

  /** Fence 初始化完成后注册唯一 LocalTCC 资源。 */
  @Bean
  @DependsOn("tccFenceConfig")
  public BalanceTccDebitLocalResource balanceTccDebitLocalResource(
      PlatformTransactionManager transactionManager,
      BalanceTccDebitBusinessCallback callback,
      TccFenceLogMapper fences,
      BalanceDebitAttemptMapper attempts,
      BalanceTccResourceEvidenceMapper evidence) {
    return new BalanceTccDebitLocalResourceImpl(
        transactionManager, callback, fences, attempts, evidence);
  }
}
