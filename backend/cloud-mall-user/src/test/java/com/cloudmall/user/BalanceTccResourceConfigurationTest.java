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
  /** 已读Fence的精确identity先于官方handler，两个回调均false/rollbackOnly且不碰资金。 */
  @Test
  void callbacksRejectForeignFenceIdentityBeforeAnyFundsOrOwnerReads() {
    for (boolean committing : java.util.List.of(false, true)) {
      for (String variant :
          java.util.List.of(
              "xid-null",
              "xid-other",
              "xid-case",
              "xid-space",
              "branch-null",
              "branch-other",
              "action-null",
              "action-other")) {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = new org.springframework.transaction.support.SimpleTransactionStatus();
        org.mockito.Mockito.when(manager.getTransaction(org.mockito.ArgumentMatchers.any()))
            .thenReturn(status);
        var funds = mock(com.cloudmall.user.service.BalanceTccDebitService.class);
        var fences = mock(TccFenceLogMapper.class);
        var attempts = mock(com.cloudmall.user.mapper.BalanceDebitAttemptMapper.class);
        var evidence = mock(com.cloudmall.user.mapper.BalanceTccResourceEvidenceMapper.class);
        var fence = new com.cloudmall.user.domain.po.TccFenceLogPO();
        fence.xid = "owned-xid";
        fence.branchId = 11L;
        fence.actionName = com.cloudmall.user.service.BalanceTccDebitLocalResource.ACTION_NAME;
        fence.status = 1;
        switch (variant) {
          case "xid-null" -> fence.xid = null;
          case "xid-other" -> fence.xid = "other-xid";
          case "xid-case" -> fence.xid = "OWNED-XID";
          case "xid-space" -> fence.xid = "owned-xid ";
          case "branch-null" -> fence.branchId = null;
          case "branch-other" -> fence.branchId = 12L;
          case "action-null" -> fence.actionName = null;
          case "action-other" -> fence.actionName = "other-action";
          default -> throw new AssertionError(variant);
        }
        org.mockito.Mockito.when(fences.findByBranch("owned-xid", 11L)).thenReturn(fence);
        var request = new com.cloudmall.user.domain.dto.BalanceTccBranchDTO();
        request.userId = 7L;
        request.payNo = "owned-pay";
        request.orderNo = "owned-order";
        request.amount = java.math.BigDecimal.TEN;
        var incoming = new io.seata.rm.tcc.api.BusinessActionContext();
        incoming.setXid("owned-xid");
        incoming.setBranchId(11L);
        incoming.setActionName(com.cloudmall.user.service.BalanceTccDebitLocalResource.ACTION_NAME);
        incoming.setActionContext(java.util.Map.of("request", request));
        var resource =
            new com.cloudmall.user.service.impl.BalanceTccDebitLocalResourceImpl(
                manager,
                new com.cloudmall.user.service.impl.BalanceTccDebitBusinessCallback(funds),
                fences,
                attempts,
                evidence);
        assertFalse(committing ? resource.commit(incoming) : resource.rollback(incoming));
        org.junit.jupiter.api.Assertions.assertTrue(status.isRollbackOnly());
        verifyNoInteractions(funds, attempts, evidence);
        org.mockito.Mockito.verify(fences).findByBranch("owned-xid", 11L);
        org.mockito.Mockito.verifyNoMoreInteractions(fences);
      }
    }
  }

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
