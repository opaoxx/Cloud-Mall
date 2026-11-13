package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.web.AuthInterceptor;
import com.cloudmall.pay.controller.PayController;
import com.cloudmall.pay.controller.PayCreationController;
import com.cloudmall.pay.controller.PayFailureController;
import com.cloudmall.pay.controller.PayQueryController;
import com.cloudmall.pay.controller.PaySuccessController;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.dto.PayCreateDTO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayCreationService;
import com.cloudmall.pay.service.PayFailureService;
import com.cloudmall.pay.service.PayQueryService;
import com.cloudmall.pay.service.PaySuccessService;
import com.cloudmall.pay.service.impl.PayCreationServiceImpl;
import com.cloudmall.pay.service.impl.PayFailureServiceImpl;
import com.cloudmall.pay.service.impl.PayQueryServiceImpl;
import com.cloudmall.pay.service.impl.PayServiceImpl;
import com.cloudmall.pay.service.impl.PaySuccessServiceImpl;
import com.cloudmall.pay.service.impl.PayWriteMonitor;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 查询与原写入口共存时的路由、Spring事务和同步监视器边界。 */
class PayQueryWiringTest {
  @AfterEach
  void clearIdentity() {
    AuthContext.clear();
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
  }

  @Test
  void queryAddsNoTransactionAndLeavesAnExistingCallerTransactionIntact() {
    var payments = mock(PayRecordMapper.class);
    var transactions = new ObservedTransactions();
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("pay-query-wiring-test");
      context.register(Transactions.class, PayQueryController.class, PayQueryServiceImpl.class);
      context.registerBean(PayRecordMapper.class, () -> payments);
      context.registerBean("transactionManager", ObservedTransactions.class, () -> transactions);
      context.refresh();
      var controller = context.getBean(PayQueryController.class);
      var service = context.getBean(PayQueryService.class);
      assertEquals(1, context.getBeansOfType(PayQueryService.class).size());
      assertFalse(AopUtils.isAopProxy(service));
      assertFalse(AopUtils.isAopProxy(controller));
      AuthContext.set(7L, "USER");
      when(payments.selectByOrderNoAndUserId("order-1", 7L))
          .thenAnswer(
              invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return List.of(new PayRecordPO());
              });
      controller.get("order-1");
      assertEquals(0, transactions.begins);

      Object callerResource = new Object();
      doAnswer(
              invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertSame(callerResource, TransactionSynchronizationManager.getResource(payments));
                return List.of(new PayRecordPO());
              })
          .when(payments)
          .selectByOrderNoAndUserId("order-1", 7L);
      new TransactionTemplate(transactions)
          .execute(
              status -> {
                TransactionSynchronizationManager.bindResource(payments, callerResource);
                try {
                  controller.get("order-1");
                  assertSame(
                      callerResource, TransactionSynchronizationManager.getResource(payments));
                  return null;
                } finally {
                  TransactionSynchronizationManager.unbindResource(payments);
                }
              });
      assertEquals(1, transactions.begins);
      assertEquals(1, transactions.commits);
      verify(payments, times(2)).selectByOrderNoAndUserId("order-1", 7L);
      verifyNoMoreInteractions(payments);
    }
  }

  @Test
  void queryAndFourOriginalPostRoutesHaveUniqueOwnersAndKeepWriteBoundaries() throws Exception {
    var payments = mock(PayRecordMapper.class);
    when(payments.selectByOrderNoAndUserId("order-1", 7L)).thenReturn(List.of(new PayRecordPO()));
    var legacy = new PayController();
    var query = new PayQueryController(new PayQueryServiceImpl(payments));
    var mvc =
        MockMvcBuilders.standaloneSetup(
                legacy,
                query,
                new PayCreationController(mock(PayCreationService.class)),
                new PaySuccessController(mock(PaySuccessService.class)),
                new PayFailureController(mock(PayFailureService.class)))
            .addInterceptors(new AuthInterceptor())
            .build();
    var mappings =
        mvc.getDispatcherServlet()
            .getWebApplicationContext()
            .getBean(RequestMappingHandlerMapping.class)
            .getHandlerMethods();
    assertEquals(5, mappings.size());
    mappings.forEach(
        (mapping, handler) -> {
          if (mapping.getMethodsCondition().getMethods().equals(Set.of(RequestMethod.GET))) {
            assertEquals(Set.of("/api/payments/orders/{orderNo}"), mapping.getPatternValues());
            assertEquals(PayQueryController.class, handler.getBeanType());
          } else if (mapping.getPatternValues().equals(Set.of("/api/payments"))) {
            assertEquals(Set.of(RequestMethod.POST), mapping.getMethodsCondition().getMethods());
            assertEquals(PayCreationController.class, handler.getBeanType());
          } else if (mapping
              .getPatternValues()
              .equals(Set.of("/api/payments/{payNo}/mock-success"))) {
            assertEquals(Set.of(RequestMethod.POST), mapping.getMethodsCondition().getMethods());
            assertEquals(PaySuccessController.class, handler.getBeanType());
          } else if (mapping.getPatternValues().equals(Set.of("/api/payments/{payNo}/mock-fail"))) {
            assertEquals(Set.of(RequestMethod.POST), mapping.getMethodsCondition().getMethods());
            assertEquals(PayFailureController.class, handler.getBeanType());
          } else {
            assertEquals(Set.of(RequestMethod.POST), mapping.getMethodsCondition().getMethods());
            assertEquals(PayController.class, handler.getBeanType());
            assertEquals(PayServiceImpl.class, handler.getMethod().getDeclaringClass());
          }
        });
    assertEquals(
        Set.of(
            "/api/payments",
            "/api/payments/orders/{orderNo}",
            "/api/payments/{payNo}/mock-success",
            "/api/payments/{payNo}/mock-fail",
            "/api/payments/callback"),
        mappings.keySet().stream()
            .flatMap(mapping -> mapping.getPatternValues().stream())
            .collect(java.util.stream.Collectors.toSet()));
    mvc.perform(get("/api/payments/orders/order-1").header("X-User-Id", "7"))
        .andExpect(status().isOk());
    assertThrows(
        NoSuchMethodException.class, () -> PayServiceImpl.class.getMethod("get", String.class));
    var create =
        PayCreationServiceImpl.class.getMethod(
            "create", String.class, PayCreateDTO.class, LongSupplier.class);
    var success =
        PaySuccessServiceImpl.class.getMethod("succeed", String.class, LongSupplier.class);
    var callback = PayServiceImpl.class.getMethod("callback", String.class, PayCallbackDTO.class);
    var failure = PayFailureServiceImpl.class.getMethod("fail", String.class, long.class);
    assertTrue(create.isAnnotationPresent(Transactional.class));
    for (var method : List.of(create, success, callback)) {
      assertFalse(Modifier.isSynchronized(method.getModifiers()));
    }
    assertFalse(Modifier.isSynchronized(failure.getModifiers()));
    for (var method : List.of(success, callback, failure)) {
      assertFalse(method.isAnnotationPresent(Transactional.class));
    }
    assertFalse(
        Modifier.isSynchronized(
            PayQueryController.class.getMethod("get", String.class).getModifiers()));
    assertFalse(
        Modifier.isSynchronized(
            PayQueryServiceImpl.class
                .getMethod("getByOrderNo", String.class, long.class)
                .getModifiers()));
  }

  @Test
  void queryCompletesWhileAnotherThreadOwnsOriginalWriteMonitor() throws Exception {
    var payments = mock(PayRecordMapper.class);
    when(payments.selectByOrderNoAndUserId("order-1", 7L)).thenReturn(List.of(new PayRecordPO()));
    var legacy = new PayController();
    var monitor = new PayWriteMonitor();
    ReflectionTestUtils.setField(legacy, "writeMonitor", monitor);
    var query = new PayQueryController(new PayQueryServiceImpl(payments));
    var executor = Executors.newSingleThreadExecutor();
    try {
      synchronized (monitor) {
        var result =
            executor.submit(
                () -> {
                  AuthContext.set(7L, "USER");
                  try {
                    return query.get("order-1");
                  } finally {
                    AuthContext.clear();
                  }
                });
        assertEquals("0", result.get(3, TimeUnit.SECONDS).code);
      }
      verify(payments).selectByOrderNoAndUserId("order-1", 7L);
      verifyNoMoreInteractions(payments);
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
    }
  }

  /** 仅显式激活本测试配置，避免其他扫描测试意外启用。 */
  @Configuration
  @Profile("pay-query-wiring-test")
  @EnableTransactionManagement
  static class Transactions {}

  /** 无数据库资源的事务观察器，记录是否新增或挂起事务。 */
  static class ObservedTransactions extends AbstractPlatformTransactionManager {
    /** 事务开始次数。 */
    private int begins;

    /** 事务提交次数。 */
    private int commits;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      begins++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      fail("unexpected rollback");
    }
  }
}
