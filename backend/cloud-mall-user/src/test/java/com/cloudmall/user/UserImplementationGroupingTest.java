package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.config.BalanceTccResourceConfiguration;
import com.cloudmall.user.config.UserTccHttpConfiguration;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.impl.evidence.UserFrozenOwnershipEvidenceReadService;
import com.cloudmall.user.service.impl.evidence.UserFrozenOwnershipEvidenceService;
import com.cloudmall.user.service.impl.evidence.UserLinkedRefundEvidenceReadService;
import com.cloudmall.user.service.impl.evidence.UserLinkedRefundEvidenceService;
import com.cloudmall.user.service.impl.evidence.UserPaymentLedgerEvidenceReadService;
import com.cloudmall.user.service.impl.evidence.UserPaymentLedgerEvidenceService;
import com.cloudmall.user.service.impl.evidence.UserTccReconciliationReadService;
import com.cloudmall.user.service.impl.evidence.UserTccReconciliationService;
import com.cloudmall.user.service.impl.evidence.UserTerminalFrozenOwnershipEvidenceReadService;
import com.cloudmall.user.service.impl.evidence.UserTerminalFrozenOwnershipEvidenceService;
import com.cloudmall.user.service.impl.tcc.BalanceTccDebitServiceImpl;
import com.cloudmall.user.service.impl.tcc.UserTccHttpService;
import com.cloudmall.user.service.impl.tcc.UserTccReceiveStore;
import com.cloudmall.user.service.impl.tcc.UserTccStateQuery;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 从新实现包真实扫描组件，核对稳定Bean名及默认关闭时无连接/资源副作用。 */
class UserImplementationGroupingTest {
  /** 以下替身只位于数据库和本地事务基础设施边界。 */
  private final UserMapper users = mock(UserMapper.class);

  /** 扣款归属访问边界。 */
  private final BalanceDebitAttemptMapper attempts = mock(BalanceDebitAttemptMapper.class);

  /** 冻结事实访问边界。 */
  private final BalanceTccDebitMapper debits = mock(BalanceTccDebitMapper.class);

  /** 账本访问边界。 */
  private final BalanceLedgerMapper ledgers = mock(BalanceLedgerMapper.class);

  /** 接收记录访问边界。 */
  private final UserTccReceiveMapper receives = mock(UserTccReceiveMapper.class);

  /** 官方Fence访问边界。 */
  private final TccFenceLogMapper fences = mock(TccFenceLogMapper.class);

  /** 未通过入口门时不得启动本地事务。 */
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  @Test
  void componentScanKeepsNamesAndDisabledEvidenceNeverStartsTransactions() {
    runner()
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              Map<String, Class<?>> expected =
                  Map.ofEntries(
                      Map.entry("balanceTccDebitServiceImpl", BalanceTccDebitServiceImpl.class),
                      Map.entry("userTccReconciliationService", UserTccReconciliationService.class),
                      Map.entry(
                          "userTccReconciliationReadService",
                          UserTccReconciliationReadService.class),
                      Map.entry(
                          "userLinkedRefundEvidenceService", UserLinkedRefundEvidenceService.class),
                      Map.entry(
                          "userLinkedRefundEvidenceReadService",
                          UserLinkedRefundEvidenceReadService.class),
                      Map.entry(
                          "userFrozenOwnershipEvidenceService",
                          UserFrozenOwnershipEvidenceService.class),
                      Map.entry(
                          "userFrozenOwnershipEvidenceReadService",
                          UserFrozenOwnershipEvidenceReadService.class),
                      Map.entry(
                          "userTerminalFrozenOwnershipEvidenceService",
                          UserTerminalFrozenOwnershipEvidenceService.class),
                      Map.entry(
                          "userTerminalFrozenOwnershipEvidenceReadService",
                          UserTerminalFrozenOwnershipEvidenceReadService.class),
                      Map.entry(
                          "userPaymentLedgerEvidenceService",
                          UserPaymentLedgerEvidenceService.class),
                      Map.entry(
                          "userPaymentLedgerEvidenceReadService",
                          UserPaymentLedgerEvidenceReadService.class));
              expected.forEach(
                  (name, type) -> {
                    assertSame(context.getBean(type), context.getBean(name));
                    assertArrayEquals(new String[] {name}, context.getBeanNamesForType(type));
                    assertEquals(
                        name.endsWith("ReadService") || name.equals("balanceTccDebitServiceImpl"),
                        AopUtils.isAopProxy(context.getBean(name)));
                  });
              for (String disabled :
                  List.of(
                      "tccFenceConfig",
                      "balanceTccDebitBusinessCallback",
                      "balanceTccDebitLocalResource",
                      "userTccHttpService",
                      "userTccReceiveStore",
                      "userTccStateQuery")) {
                assertFalse(context.containsBean(disabled));
              }
              List<Runnable> reads =
                  List.of(
                      () ->
                          context
                              .getBean(UserTccReconciliationService.class)
                              .inspect(
                                  7L, "owned", "cm18-reconciliation", "cloudmall-local-callback"),
                      () ->
                          context
                              .getBean(UserLinkedRefundEvidenceService.class)
                              .inspect(
                                  7L, "owned", "cm18-reconciliation", "cloudmall-local-callback"),
                      () ->
                          context
                              .getBean(UserFrozenOwnershipEvidenceService.class)
                              .inspect(
                                  7L, "owned", "cm18-reconciliation", "cloudmall-local-callback"),
                      () ->
                          context
                              .getBean(UserTerminalFrozenOwnershipEvidenceService.class)
                              .inspect(
                                  7L, "owned", "cm18-reconciliation", "cloudmall-local-callback"),
                      () ->
                          context
                              .getBean(UserPaymentLedgerEvidenceService.class)
                              .inspect(
                                  7L, "owned", "cm18-reconciliation", "cloudmall-local-callback"));
              for (Runnable read : reads) {
                BizException disabled = assertThrows(BizException.class, read::run);
                assertEquals(503, disabled.getHttpStatus());
                assertTrue(disabled.getCode().endsWith("_DISABLED"));
              }
              verifyNoInteractions(
                  users, attempts, debits, ledgers, receives, fences, transactions);
            });
  }

  @Test
  void enablingHttpWithoutResourceFailsBeforePreparingReceiveTable() {
    runner()
        .withPropertyValues("cloudmall.user.tcc-http.enabled=true")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              Throwable cause = context.getStartupFailure();
              while (cause.getCause() != null) cause = cause.getCause();
              assertInstanceOf(
                  org.springframework.beans.factory.NoSuchBeanDefinitionException.class, cause);
              assertTrue(cause.getMessage().contains(BalanceTccDebitLocalResource.class.getName()));
              verifyNoInteractions(
                  users, attempts, debits, ledgers, receives, fences, transactions);
            });
  }

  @Test
  void enabledHttpConfigurationKeepsStoreQueryAndBridgeBeanNames() {
    BalanceTccDebitLocalResource resource = mock(BalanceTccDebitLocalResource.class);
    runner()
        .withPropertyValues("cloudmall.user.tcc-http.enabled=true")
        .withBean(BalanceTccDebitLocalResource.class, () -> resource)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertSame(
                  context.getBean(UserTccReceiveStore.class),
                  context.getBean("userTccReceiveStore"));
              assertSame(
                  context.getBean(UserTccStateQuery.class), context.getBean("userTccStateQuery"));
              assertSame(
                  context.getBean(UserTccHttpService.class), context.getBean("userTccHttpService"));
              assertTrue(AopUtils.isAopProxy(context.getBean(UserTccReceiveStore.class)));
              assertTrue(AopUtils.isAopProxy(context.getBean(UserTccStateQuery.class)));
              assertFalse(AopUtils.isAopProxy(context.getBean(UserTccHttpService.class)));
              verify(receives).ensureTable();
              verifyNoMoreInteractions(receives);
              verifyNoInteractions(
                  resource, users, attempts, debits, ledgers, fences, transactions);
            });
  }

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withPropertyValues("spring.profiles.active=user-grouping-test")
        .withUserConfiguration(GroupedServices.class)
        .withBean(UserMapper.class, () -> users)
        .withBean(BalanceDebitAttemptMapper.class, () -> attempts)
        .withBean(BalanceTccDebitMapper.class, () -> debits)
        .withBean(BalanceLedgerMapper.class, () -> ledgers)
        .withBean(UserTccReceiveMapper.class, () -> receives)
        .withBean(TccFenceLogMapper.class, () -> fences)
        .withBean(PlatformTransactionManager.class, () -> transactions);
  }

  /** 只扫描本次归组包，并加载原条件配置及实际Spring事务代理。 */
  @Configuration(proxyBeanMethods = false)
  @Profile("user-grouping-test")
  @EnableTransactionManagement(proxyTargetClass = true)
  @ComponentScan(
      basePackageClasses = {BalanceTccDebitServiceImpl.class, UserTccReconciliationService.class})
  @Import({BalanceTccResourceConfiguration.class, UserTccHttpConfiguration.class})
  static class GroupedServices {}
}
