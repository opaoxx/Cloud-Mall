package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.cloudmall.user.config.BalanceTccResourceConfiguration;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;

/** 错误的 Seata/事务配置必须在操作 Fence 表前失败。 */
class BalanceTccResourceConfigurationTest {
  @Test
  void defaultSettingsDoNotCreateARmOrFenceConfiguration() {
    new ApplicationContextRunner()
        .withUserConfiguration(BalanceTccResourceConfiguration.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertFalse(context.containsBean("balanceTccDebitLocalResource"));
              assertFalse(context.containsBean("tccFenceConfig"));
            });
  }

  @Test
  void disabledSeataCannotRegisterAFundResource() {
    DataSource dataSource = mock(DataSource.class);
    TccFenceLogMapper mapper = mock(TccFenceLogMapper.class);
    assertThrows(
        IllegalStateException.class,
        () ->
            new BalanceTccResourceConfiguration()
                .tccFenceConfig(
                    dataSource,
                    new DataSourceTransactionManager(dataSource),
                    mapper,
                    new MockEnvironment()));
    verifyNoInteractions(dataSource, mapper);
  }

  @Test
  void automaticAtProxyCannotBeMixedIntoLocalTccFunds() {
    DataSource dataSource = mock(DataSource.class);
    TccFenceLogMapper mapper = mock(TccFenceLogMapper.class);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("seata.enabled", "true")
            .withProperty("seata.enable-auto-data-source-proxy", "true");
    assertThrows(
        IllegalStateException.class,
        () ->
            new BalanceTccResourceConfiguration()
                .tccFenceConfig(
                    dataSource, new DataSourceTransactionManager(dataSource), mapper, environment));
    verifyNoInteractions(dataSource, mapper);
  }

  @Test
  void fenceAndFundsCannotUseDifferentDataSources() {
    DataSource dataSource = mock(DataSource.class);
    DataSource otherDataSource = mock(DataSource.class);
    TccFenceLogMapper mapper = mock(TccFenceLogMapper.class);
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("seata.enabled", "true")
            .withProperty("seata.enable-auto-data-source-proxy", "false");
    assertThrows(
        IllegalStateException.class,
        () ->
            new BalanceTccResourceConfiguration()
                .tccFenceConfig(
                    dataSource,
                    new DataSourceTransactionManager(otherDataSource),
                    mapper,
                    environment));
    verifyNoInteractions(dataSource, otherDataSource, mapper);
  }
}
