package com.cloudmall.order.config;

import io.seata.rm.datasource.DataSourceProxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.Advised;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/** 审计订单路由运行时绑定关系，并向 Actuator 提供不含凭据的只读摘要。 */
@Component
public class OrderRoutingAudit implements InfoContributor {
  /** 只记录审计白名单字段，不使用DataSource.toString或连接配置。 */
  private static final Logger LOG = LoggerFactory.getLogger(OrderRoutingAudit.class);

  /** 启动时核验的底层类型，不在info请求时触发wrapper解析。 */
  private final String underlyingType;

  /** 选定的 Spring DataSource Bean。 */
  private final DataSource dataSource;

  /** 绑定同一 DataSource 的 JdbcTemplate。 */
  private final JdbcTemplate jdbcTemplate;

  /** 绑定同一 DataSource 的事务管理器。 */
  private final PlatformTransactionManager transactionManager;

  /** Spring 环境，仅用于读取安全配置摘要。 */
  private final Environment environment;

  /** Completed explicit bootstrap; absent only on the original dynamic construction path. */
  private final OrderShardingBootstrap bootstrap;

  /** Spring injection checks the explicit snapshot without changing old direct callers. */
  @Autowired
  public OrderRoutingAudit(DataSource dataSource, JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager, Environment environment,
      ObjectProvider<OrderShardingBootstrap> provider) {
    this(dataSource, jdbcTemplate, transactionManager, environment,
        OrderRoutingConfiguration.SHARDING_MONTHLY.equals(OrderRoutingConfiguration.mode(environment))
            ? requireBootstrap(provider) : null);
  }

  /** 创建并立即审计 DataSource、JdbcTemplate 和事务管理器对象绑定。 */
  public OrderRoutingAudit(
      DataSource dataSource,
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager,
      Environment environment) {
    this(dataSource, jdbcTemplate, transactionManager, environment, (OrderShardingBootstrap) null);
  }

  private OrderRoutingAudit(DataSource dataSource, JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager, Environment environment, OrderShardingBootstrap bootstrap) {
    if (OrderRoutingConfiguration.SHARDING_MONTHLY.equals(OrderRoutingConfiguration.mode(environment)) && bootstrap == null)
      throw new IllegalStateException("ORDER_SHARD_BOOTSTRAP_NOT_READY");
    this.bootstrap = bootstrap;
    this.dataSource = dataSource;
    this.jdbcTemplate = jdbcTemplate;
    this.transactionManager = transactionManager;
    this.environment = environment;
    validateBinding();
    if (bootstrap != null) bootstrap.verifyBinding(dataSource);
    this.underlyingType = bootstrap == null ? simpleName(underlyingDataSource(dataSource)) : "HikariDataSource";
    LOG.info(
        "订单路由审计 mode={}, dataSource={}, underlying={}, transactionManager={}, identity={},"
            + " profiles={}, expectedMonths={}",
        OrderRoutingConfiguration.mode(environment),
        simpleName(dataSource),
        underlyingType,
        simpleName(transactionManager),
        Integer.toHexString(System.identityHashCode(dataSource)),
        sanitizedProfiles(),
        expectedMonths());
  }

  /** 向 Actuator 提供安全清洗后的订单路由信息，不执行数据库或 RPC 操作。 */
  @Override
  public void contribute(Info.Builder builder) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("routingMode", OrderRoutingConfiguration.mode(environment));
    details.put("activeProfiles", sanitizedProfiles());
    details.put("dataSourceType", simpleName(dataSource));
    details.put("underlyingDataSourceType", underlyingType);
    details.put("transactionManagerType", simpleName(transactionManager));
    details.put("expectedMonths", expectedMonths());
    if (bootstrap != null) details.putAll(bootstrap.info());
    builder.withDetail("orderRouting", details);
  }

  /** 校验选定三件对象的身份绑定和事务管理器类型。 */
  private void validateBinding() {
    if (!(transactionManager instanceof DataSourceTransactionManager dataSourceManager)
        || dataSourceManager.getDataSource() != dataSource
        || jdbcTemplate.getDataSource() != dataSource) throw unsupported();
  }

  /** 判断数据源本身或受支持 wrapper 内部是否为 Sharding 数据源。 */
  private boolean isShardingDataSource(DataSource candidate) {
    for (Class<?> type = candidate.getClass(); type != null; type = type.getSuperclass()) {
      if (type.getName().startsWith("org.apache.shardingsphere.")) return true;
    }
    return false;
  }

  /** 仅读取 wrapper 的 target 类型，不调用 getConnection。 */
  private DataSource underlyingDataSource(DataSource candidate) {
    var visited = new IdentityHashMap<DataSource, Boolean>();
    for (int depth = 0; depth < 16; depth++) {
      if (candidate == null
          || visited.put(candidate, Boolean.TRUE) != null
          || isShardingDataSource(candidate)) throw unsupported();
      if (candidate instanceof Advised advised) {
        try {
          if (!advised.getTargetSource().isStatic()) throw unsupported();
          Object target = advised.getTargetSource().getTarget();
          if (!(target instanceof DataSource)) throw unsupported();
          candidate = (DataSource) target;
        } catch (Exception unreadable) {
          throw unsupported();
        }
      } else if (candidate instanceof DataSourceProxy proxy) {
        candidate = proxy.getTargetDataSource();
      } else if (candidate instanceof DelegatingDataSource proxy) {
        candidate = proxy.getTargetDataSource();
      } else {
        return candidate;
      }
    }
    throw unsupported();
  }

  /** 清洗 profile 名称，异常值统一 redacted。 */
  private List<String> sanitizedProfiles() {
    List<String> profiles = new ArrayList<>();
    for (String profile : environment.getActiveProfiles())
      profiles.add(
          profile != null && profile.matches("[A-Za-z0-9_-]{1,32}") ? profile : "redacted");
    return profiles;
  }

  /** 与建表器复用目标月份计算；不把它当作实际表存在性证明。 */
  private List<String> expectedMonths() {
    return OrderTableInitializer.rollingMonths(
        java.time.YearMonth.now(java.time.ZoneId.of("Asia/Shanghai")));
  }

  private static OrderShardingBootstrap requireBootstrap(ObjectProvider<OrderShardingBootstrap> provider) {
    OrderShardingBootstrap bootstrap = provider.getIfAvailable();
    if (bootstrap == null) throw new IllegalStateException("ORDER_SHARD_BOOTSTRAP_NOT_READY");
    bootstrap.requireReady();
    return bootstrap;
  }

  private String simpleName(Object value) {
    return value.getClass().getSimpleName();
  }

  private IllegalStateException unsupported() {
    return new IllegalStateException(OrderRoutingConfiguration.UNSUPPORTED);
  }
}
