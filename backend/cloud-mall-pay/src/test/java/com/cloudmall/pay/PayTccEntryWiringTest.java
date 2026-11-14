package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.config.PayTccEntryConfiguration;
import com.cloudmall.pay.config.PayTccReceiptConfiguration;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.PayTccDeliveryService;
import com.cloudmall.pay.service.impl.cancellation.PayTccAbortService;
import com.cloudmall.pay.service.impl.cancellation.PayTccCancelService;
import com.cloudmall.pay.service.impl.cancellation.PayTccCommittedCancelService;
import com.cloudmall.pay.service.impl.delivery.PayTccDeliveryScheduler;
import com.cloudmall.pay.service.impl.entry.PayTccPaymentService;
import com.cloudmall.pay.service.impl.execution.PayTccRecoveryScheduler;
import com.cloudmall.pay.service.impl.projection.PayTccPublicStateStore;
import com.cloudmall.pay.service.impl.projection.PayTccRollbackProjectionService;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 实际扫描公开TCC入口及原前置配置；业务、TC配置依赖和事务管理器使用替身。 */
class PayTccEntryWiringTest {
  @Test
  void scanPreservesEntryGateOriginalBeanTransactionsAndOptionalCancellation() throws Exception {
    for (int scenario = 0; scenario < 3; scenario++) {
      boolean enabled = scenario > 0;
      boolean optionalCancellation = scenario == 2;
      try (var fixture = new Fixture(enabled, optionalCancellation, null)) {
        // 1. 原配置调用Receipt校验；关闭入口时不扫描出路由Bean。
        fixture.context.refresh();
        if (enabled) verify(fixture.mockOf(PayTccReceiptConfiguration.class)).validate();
        verifyNoMoreInteractions(fixture.dependencies.values().toArray());
        clearInvocations(fixture.dependencies.values().toArray());
        Set<String> actual = new HashSet<>();
        for (String name : fixture.context.getBeanDefinitionNames()) {
          if (AopUtils.getTargetClass(fixture.context.getBean(name))
              .getPackageName()
              .equals("com.cloudmall.pay.service.impl.entry")) actual.add(name);
        }
        assertEquals(enabled ? Set.of("payTccPaymentService") : Set.of(), actual);
        assertEquals(enabled, fixture.context.containsBean("payTccEntryConfiguration"));
        var entry = fixture.context.getBeanProvider(PayTccPaymentService.class).getIfAvailable();
        if (!enabled) {
          assertNull(entry);
          verifyNoInteractions(fixture.dependencies.values().toArray());
          continue;
        }
        assertNotNull(entry);
        assertTrue(AopUtils.isAopProxy(entry));

        // 2. pay不新增事务，failure只保留原NOT_SUPPORTED；字段仍按可选依赖注入。
        var attributes = new AnnotationTransactionAttributeSource();
        assertNull(
            attributes.getTransactionAttribute(
                PayTccPaymentService.class.getMethod("pay", PayVO.class),
                PayTccPaymentService.class));
        var failureAttribute =
            attributes.getTransactionAttribute(
                PayTccPaymentService.class.getMethod("failure", PayVO.class),
                PayTccPaymentService.class);
        assertNotNull(failureAttribute);
        assertEquals(
            TransactionDefinition.PROPAGATION_NOT_SUPPORTED,
            failureAttribute.getPropagationBehavior());
        assertEquals(TransactionDefinition.ISOLATION_DEFAULT, failureAttribute.getIsolationLevel());
        assertFalse(failureAttribute.isReadOnly());
        Object target = AopTestUtils.getTargetObject(entry);
        assertSame(
            fixture.mockOf(PayTccCancelMapper.class),
            ReflectionTestUtils.getField(target, "cancellations"));
        Map<String, Class<?>> optional =
            Map.of(
                "cancelService", PayTccCancelService.class,
                "abortService", PayTccAbortService.class,
                "committedCancelService", PayTccCommittedCancelService.class);
        optional.forEach(
            (field, type) ->
                assertSame(
                    fixture.dependencies.get(type), ReflectionTestUtils.getField(target, field)));

        // 3. 终态直接返回/拒绝；可选取消关闭保持202，存在时只走原无execution取消分支。
        for (String terminal : List.of("SUCCESS", "FAILED", "REFUNDED")) {
          PayVO payment = payment(terminal);
          assertEquals(terminal, entry.pay(payment));
          if (terminal.equals("FAILED")) entry.failure(payment);
          else
            assertEquals(
                409,
                assertThrows(BizException.class, () -> entry.failure(payment)).getHttpStatus());
        }
        verifyNoInteractions(fixture.dependencies.values().toArray());
        PayVO payment = payment("PENDING");
        if (optionalCancellation) {
          entry.failure(payment);
          assertEquals("FAILED", payment.status);
          verify(fixture.mockOf(PayTccCancelService.class)).request(payment);
        } else {
          var failure = assertThrows(BizException.class, () -> entry.failure(payment));
          assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
          assertEquals(202, failure.getHttpStatus());
          assertEquals("PENDING", payment.status);
        }
        verify(fixture.mockOf(PayTccPublicStateStore.class)).execution(payment.payNo);
        verifyNoMoreInteractions(fixture.dependencies.values().toArray());
      }
    }
  }

  @Test
  void originalEntryConfigurationStillRequiresReceiptRecoveryAndDelivery() {
    for (Class<?> missing :
        List.of(
            PayTccReceiptConfiguration.class,
            PayTccRecoveryScheduler.class,
            PayTccDeliveryScheduler.class)) {
      try (var fixture = new Fixture(true, false, missing)) {
        var failure = assertThrows(BeanCreationException.class, fixture.context::refresh);
        assertTrue(failure.getMessage().contains(missing.getSimpleName()));
        verifyNoInteractions(fixture.dependencies.values().toArray());
      }
    }
  }

  /** 最小路由视图，未决分支仅执行受控依赖调用。 */
  private PayVO payment(String status) {
    PayVO payment = new PayVO();
    payment.payNo = "entry-wiring-payment";
    payment.status = status;
    return payment;
  }

  /** 专用Profile避免被其它完整应用测试误扫描，不启用调度或真实TC。 */
  @Configuration
  @Profile("pay-tcc-entry-wiring-test")
  @ComponentScan(basePackageClasses = PayTccPaymentService.class)
  @EnableTransactionManagement
  static class ScannedInfrastructure {}

  /** 外部依赖受控，原EntryConfiguration的条件与构造依赖保持。 */
  private static final class Fixture implements AutoCloseable {
    /** 显式刷新的Spring上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 除事务基础设施外需要核对交互的依赖。 */
    private final Map<Class<?>, Object> dependencies = new LinkedHashMap<>();

    private Fixture(boolean enabled, boolean optionalCancellation, Class<?> missing) {
      context.getEnvironment().setActiveProfiles("pay-tcc-entry-wiring-test");
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "entryFixture",
                  enabled ? Map.of("cloudmall.pay.tcc-entry.enabled", true) : Map.of()));
      for (Class<?> type :
          List.of(
              PayTccReceiptConfiguration.class,
              PayTccRecoveryScheduler.class,
              PayTccDeliveryScheduler.class,
              PayTccCoordinatorService.class,
              PayTccDeliveryService.class,
              PayTccPublicStateStore.class,
              OrderClient.class,
              PayTccRollbackProjectionService.class,
              PayTccCancelMapper.class)) {
        if (type != missing) register(type);
      }
      if (optionalCancellation) {
        for (Class<?> type :
            List.of(
                PayTccCancelService.class,
                PayTccAbortService.class,
                PayTccCommittedCancelService.class)) register(type);
      }
      var transactions = mock(PlatformTransactionManager.class);
      when(transactions.getTransaction(any()))
          .thenAnswer(invocation -> new SimpleTransactionStatus());
      context.getBeanFactory().registerSingleton("transactionManager", transactions);
      context.register(ScannedInfrastructure.class, PayTccEntryConfiguration.class);
    }

    private void register(Class<?> type) {
      Object dependency = mock(type);
      dependencies.put(type, dependency);
      context.getBeanFactory().registerSingleton(type.getSimpleName(), dependency);
    }

    private <T> T mockOf(Class<T> type) {
      return type.cast(dependencies.get(type));
    }

    /** 关闭本测试上下文，不运行TC/Fence或定时恢复。 */
    @Override
    public void close() {
      context.close();
    }
  }
}
