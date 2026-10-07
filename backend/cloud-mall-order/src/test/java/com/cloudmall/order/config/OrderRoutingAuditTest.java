package com.cloudmall.order.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.mapper.SeckillOrderDedupMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.datasource.ShardingDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

/** Order 路由启动审计与只读运行信息的边界测试。 */
class OrderRoutingAuditTest {
  @Test
  void defaultBindingUsesTheSameDataSourceIdentityWithoutOpeningConnection() {
    FakeDataSource dataSource = new FakeDataSource();
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);

    OrderRoutingAudit audit =
        new OrderRoutingAudit(dataSource, jdbcTemplate, transactionManager, new MockEnvironment());
    Info.Builder builder = new Info.Builder();
    audit.contribute(builder);
    Map<?, ?> routing = (Map<?, ?>) builder.build().getDetails().get("orderRouting");
    assertEquals("dynamic-monthly", routing.get("routingMode"));
    assertEquals(List.of(), routing.get("activeProfiles"));
    assertEquals(
        OrderTableInitializer.rollingMonths(YearMonth.now(java.time.ZoneId.of("Asia/Shanghai"))),
        routing.get("expectedMonths"));
    assertEquals("FakeDataSource", routing.get("dataSourceType"));
    assertEquals("FakeDataSource", routing.get("underlyingDataSourceType"));
    assertEquals("DataSourceTransactionManager", routing.get("transactionManagerType"));
  }

  @Test
  void knownSpringProxyWrappingTheSameDataSourceIsAllowedWithoutConnectionProbe() throws Exception {
    FakeDataSource raw = new FakeDataSource();
    DataSource proxy = (DataSource) new ProxyFactory(raw).getProxy();
    JdbcTemplate jdbcTemplate = new JdbcTemplate(proxy);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(proxy);

    OrderRoutingAudit audit =
        new OrderRoutingAudit(proxy, jdbcTemplate, transactionManager, new MockEnvironment());
    Info.Builder builder = new Info.Builder();
    audit.contribute(builder);
    Map<?, ?> routing = (Map<?, ?>) builder.build().getDetails().get("orderRouting");
    assertTrue(routing.get("dataSourceType").toString().contains("Proxy"));
    assertEquals("FakeDataSource", routing.get("underlyingDataSourceType"));
  }

  @Test
  void differentJdbcAndTransactionDataSourceObjectsAreRejected() {
    FakeDataSource selected = new FakeDataSource();
    FakeDataSource other = new FakeDataSource();
    assertThrows(
        IllegalStateException.class,
        () ->
            new OrderRoutingAudit(
                selected,
                new JdbcTemplate(selected),
                new DataSourceTransactionManager(other),
                new MockEnvironment()));
  }

  @Test
  void shardingDataSourceAndKnownWrapperAreRejectedWithoutOpeningConnection() throws Exception {
    ShardingDataSource sharding = mock(ShardingDataSource.class);
    assertThrows(
        IllegalStateException.class,
        () ->
            new OrderRoutingAudit(
                sharding,
                new JdbcTemplate(sharding),
                new DataSourceTransactionManager(sharding),
                new MockEnvironment()));

    DelegatingDataSource wrapper = new DelegatingDataSource(sharding);
    assertThrows(
        IllegalStateException.class,
        () ->
            new OrderRoutingAudit(
                wrapper,
                new JdbcTemplate(wrapper),
                new DataSourceTransactionManager(wrapper),
                new MockEnvironment()));
  }

  @Test
  void proxyAndRawDataSourceWithSameUnderlyingObjectButDifferentBindingAreRejected() {
    FakeDataSource raw = new FakeDataSource();
    DataSource proxy = new DelegatingDataSource(raw);
    assertThrows(
        IllegalStateException.class,
        () ->
            new OrderRoutingAudit(
                proxy,
                new JdbcTemplate(proxy),
                new DataSourceTransactionManager(raw),
                new MockEnvironment()));
  }

  @Test
  void nullAndCyclicKnownWrappersAreRejectedWithoutConnectionProbe() throws Exception {
    DelegatingDataSource nullTarget = new DelegatingDataSource();
    assertThrows(
        IllegalStateException.class,
        () ->
            new OrderRoutingAudit(
                nullTarget,
                new JdbcTemplate(nullTarget),
                new DataSourceTransactionManager(nullTarget),
                new MockEnvironment()));

    DelegatingDataSource cycle = new DelegatingDataSource();
    cycle.setTargetDataSource(cycle);
    assertThrows(
        IllegalStateException.class,
        () ->
            new OrderRoutingAudit(
                cycle,
                new JdbcTemplate(cycle),
                new DataSourceTransactionManager(cycle),
                new MockEnvironment()));
  }

  @Test
  void profilesAreRedactedAndMonthWindowHandlesYearBoundary() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev", "http://x");
    FakeDataSource dataSource = new FakeDataSource();
    OrderRoutingAudit audit =
        new OrderRoutingAudit(
            dataSource,
            new JdbcTemplate(dataSource),
            new DataSourceTransactionManager(dataSource),
            environment);
    Info.Builder builder = new Info.Builder();
    audit.contribute(builder);
    Map<?, ?> routing = (Map<?, ?>) builder.build().getDetails().get("orderRouting");
    assertEquals(List.of("dev", "redacted"), routing.get("activeProfiles"));
    assertEquals(
        List.of("202611", "202612", "202701", "202702"),
        OrderTableInitializer.rollingMonths(YearMonth.of(2026, 12)));
    assertEquals(
        List.of("202612", "202701", "202702", "202703"),
        OrderTableInitializer.rollingMonths(YearMonth.of(2027, 1)));
    String rendered = routing.toString();
    assertFalse(rendered.contains("http://x"));
  }

  @Test
  void unsupportedModeFailsBeforeDataSourceBeanOrDdlMapperIsCreated() {
    EarlyGuardBeans.dataSourceCreated = false;
    EarlyGuardBeans.ddlMapperCreated = false;
    new ApplicationContextRunner()
        .withUserConfiguration(OrderRoutingConfiguration.class, EarlyGuardBeans.class)
        .withPropertyValues("cloudmall.order.routing.mode=sharding")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(
                  context.getStartupFailure().toString().contains("ORDER_ROUTING_UNSUPPORTED"));
              assertFalse(EarlyGuardBeans.dataSourceCreated);
              assertFalse(EarlyGuardBeans.ddlMapperCreated);
            });
  }

  @Test
  void shardingProfileFailsBeforeDataSourceBeanOrDdlMapperIsCreated() {
    EarlyGuardBeans.dataSourceCreated = false;
    EarlyGuardBeans.ddlMapperCreated = false;
    new ApplicationContextRunner()
        .withUserConfiguration(OrderRoutingConfiguration.class, EarlyGuardBeans.class)
        .withPropertyValues("spring.profiles.active=sharding")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertFalse(EarlyGuardBeans.dataSourceCreated);
              assertFalse(EarlyGuardBeans.ddlMapperCreated);
            });
  }

  @Test
  void shardingDefaultProfileFailsBeforeDataSourceBeanOrDdlMapperIsCreated() {
    EarlyGuardBeans.dataSourceCreated = false;
    EarlyGuardBeans.ddlMapperCreated = false;
    new ApplicationContextRunner()
        .withUserConfiguration(OrderRoutingConfiguration.class, EarlyGuardBeans.class)
        .withPropertyValues("spring.profiles.default=sharding")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(
                  context.getStartupFailure().toString().contains("ORDER_ROUTING_UNSUPPORTED"));
              assertFalse(EarlyGuardBeans.dataSourceCreated);
              assertFalse(EarlyGuardBeans.ddlMapperCreated);
            });
  }

  @Test
  void invalidRuntimeDataSourceStopsBothInitializersBeforeAnyDdl() {
    RuntimeBeans.ddlMapperCreated = false;
    new ApplicationContextRunner()
        .withUserConfiguration(RuntimeBeans.class)
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(
                  context.getStartupFailure().toString().contains("ORDER_ROUTING_UNSUPPORTED"));
              assertFalse(RuntimeBeans.ddlMapperCreated);
            });
  }

  @Test
  void primaryDataSourceWinsWhenASecondaryDataSourceExists() {
    new ApplicationContextRunner()
        .withUserConfiguration(MultipleDataSourceBeans.class)
        .run(context -> assertTrue(context.isRunning()));
  }

  @Test
  void multipleDataSourcesWithoutPrimaryFailAtSpringBinding() {
    new ApplicationContextRunner()
        .withUserConfiguration(NoPrimaryDataSourceBeans.class)
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(context.getStartupFailure().toString().contains("NoUniqueBeanDefinition"));
            });
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class EarlyGuardBeans {
    static boolean dataSourceCreated;
    static boolean ddlMapperCreated;

    @Bean
    DataSource dataSource() {
      dataSourceCreated = true;
      throw new AssertionError("DataSource must not be instantiated by early guard");
    }

    @Bean
    OrderSqlMapper orderSqlMapper() {
      ddlMapperCreated = true;
      return mock(OrderSqlMapper.class);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class RuntimeBeans {
    static boolean ddlMapperCreated;

    @Bean(name = "orderRoutingAudit")
    OrderRoutingAudit orderRoutingAudit(
        DataSource dataSource,
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager) {
      return new OrderRoutingAudit(
          dataSource, jdbcTemplate, transactionManager, new MockEnvironment());
    }

    @Bean
    DataSource dataSource() {
      return mock(ShardingDataSource.class);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
      return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    @DependsOn("orderRoutingAudit")
    OrderTableInitializer orderTableInitializer() {
      return new OrderTableInitializer(
          (OrderSqlMapper)
              mock(
                  OrderSqlMapper.class,
                  invocation -> {
                    ddlMapperCreated = true;
                    return null;
                  }));
    }

    @Bean
    @DependsOn("orderRoutingAudit")
    OrderReliabilityTableInitializer orderReliabilityTableInitializer() {
      return new OrderReliabilityTableInitializer(
          mock(OrderReservationIntentMapper.class),
          mock(OrderOutboxMapper.class),
          mock(OrderStockActionMapper.class),
          mock(SeckillOrderDedupMapper.class),
          mock(SeckillMessageRecoveryMapper.class));
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class MultipleDataSourceBeans {
    @Bean
    @Primary
    DataSource primaryDataSource() {
      return new FakeDataSource();
    }

    @Bean
    DataSource secondaryDataSource() {
      return new FakeDataSource();
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource primaryDataSource) {
      return new JdbcTemplate(primaryDataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource primaryDataSource) {
      return new DataSourceTransactionManager(primaryDataSource);
    }

    @Bean
    OrderRoutingAudit orderRoutingAudit(
        DataSource primaryDataSource,
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager) {
      return new OrderRoutingAudit(
          primaryDataSource, jdbcTemplate, transactionManager, new MockEnvironment());
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class NoPrimaryDataSourceBeans {
    @Bean
    DataSource firstDataSource() {
      return new FakeDataSource();
    }

    @Bean
    DataSource secondDataSource() {
      return new FakeDataSource();
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
      return new JdbcTemplate(dataSource);
    }
  }

  static class FakeDataSource extends AbstractDataSource {
    @Override
    public Connection getConnection() throws SQLException {
      throw new AssertionError("routing audit must not open a connection");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      throw new AssertionError("routing audit must not open a connection");
    }
  }
}
