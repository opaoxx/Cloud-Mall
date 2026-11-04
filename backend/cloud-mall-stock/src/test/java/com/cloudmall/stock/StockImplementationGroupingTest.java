package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.stock.config.StockDependencyAspect;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockConsistencyAuditStateMapper;
import com.cloudmall.stock.mapper.StockSalesOutboxMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.SeckillReservationQueryService;
import com.cloudmall.stock.service.impl.cache.StockCacheReconciler;
import com.cloudmall.stock.service.impl.cache.StockConsistencyReconciler;
import com.cloudmall.stock.service.impl.metrics.StockSalesOutboxService;
import com.cloudmall.stock.service.impl.seckill.SeckillOrderOutboxDispatcher;
import com.cloudmall.stock.service.impl.seckill.SeckillQuotaSettlementRecovery;
import com.cloudmall.stock.util.StockDependencyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** 真正扫描业务包和注册调度，但由受控调度器阻止测试自动执行资源任务。 */
class StockImplementationGroupingTest {
  @Test
  void defaultOffAndEnabledScansKeepBeanNamesTransactionProxiesAndSchedules() {
    for (boolean enabled : new boolean[] {false, true}) {
      var scheduled = new ArrayList<ScheduledMethodRunnable>();
      try (var context = context(enabled, scheduled)) {
        for (String name :
            List.of(
                "stockCacheReconciler",
                "stockConsistencyReconciler",
                "seckillReservationServiceImpl",
                "seckillRequestClaimService",
                "seckillAcceptanceService",
                "seckillQuotaSettlementService",
                "seckillQuotaSettlementRecovery",
                "seckillOrderOutboxDispatcher",
                "seckillReservationQueryService")) assertTrue(context.containsBean(name), name);
        assertFalse(context.containsBean("seckillReservationQueryServiceImpl"));
        assertTrue(AopUtils.isAopProxy(context.getBean("seckillReservationQueryService")));
        assertTrue(AopUtils.isAopProxy(context.getBean("seckillAcceptanceService")));
        assertEquals(enabled, context.containsBean("stockSalesOutboxService"));
        assertEquals(enabled, context.containsBean("stockSalesOutboxDispatcher"));
        if (enabled)
          assertTrue(AopUtils.isAopProxy(context.getBean(StockSalesOutboxService.class)));
        Set<String> tasks = new HashSet<>();
        for (var task : scheduled)
          tasks.add(
              AopUtils.getTargetClass(task.getTarget()).getSimpleName()
                  + "#"
                  + task.getMethod().getName());
        var expected =
            new HashSet<>(
                Set.of(
                    "StockCacheReconciler#repairPending",
                    "StockCacheReconciler#retryIncompleteCalibration",
                    "StockCacheReconciler#unblockResolvedLegacyStocks",
                    "StockConsistencyReconciler#scheduledAudit",
                    "SeckillQuotaSettlementRecovery#scheduledRecover",
                    "SeckillOrderOutboxDispatcher#dispatch"));
        if (enabled) expected.add("StockSalesOutboxDispatcher#dispatch");
        assertEquals(expected, tasks);
        assertEquals(expected.size(), scheduled.size());
      }
    }
  }

  @Test
  void movedReadAndRecoveryEntrypointsStillEnterTheirOriginalGuards() {
    try (var context = context(false, new ArrayList<>())) {
      var stocks = context.getBean(StockSqlMapper.class);
      var settlements = context.getBean(SeckillQuotaSettlementMapper.class);
      when(stocks.findSeckillByOrderNo("probe"))
          .thenAnswer(
              invocation -> {
                assertTrue(StockDependencyGuard.mysqlActive());
                return null;
              });
      assertEquals(
          "NOT_FOUND", context.getBean(SeckillReservationQueryService.class).find("probe").status);
      when(stocks.findPendingCacheRepairs(any(), anyInt()))
          .thenAnswer(
              invocation -> {
                assertTrue(StockDependencyGuard.mysqlActive());
                return List.of();
              });
      context.getBean(StockCacheReconciler.class).repairPending();
      when(settlements.findDue(any(), anyInt()))
          .thenAnswer(
              invocation -> {
                assertTrue(StockDependencyGuard.mysqlActive());
                return List.of();
              });
      assertEquals(0, context.getBean(SeckillQuotaSettlementRecovery.class).recoverDue());
      context.getBean(SeckillQuotaSettlementRecovery.class).scheduledRecover();
      when(stocks.findPendingSeckillReservations(anyInt()))
          .thenAnswer(
              invocation -> {
                assertTrue(StockDependencyGuard.mysqlActive());
                return List.of();
              });
      context.getBean(SeckillOrderOutboxDispatcher.class).dispatch();
      var redis = context.getBean(StringRedisTemplate.class);
      when(redis.opsForValue().setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
          .thenAnswer(
              invocation -> {
                assertTrue(StockDependencyGuard.mysqlActive());
                return false;
              });
      context.getBean(StockConsistencyReconciler.class).scheduledAudit();
      assertFalse(StockDependencyGuard.mysqlActive());
      verify(stocks).findPendingCacheRepairs(any(), anyInt());
      verify(stocks).findPendingSeckillReservations(anyInt());
      verify(settlements, atLeastOnce()).findDue(any(), anyInt());
    }
  }

  private static AnnotationConfigApplicationContext context(
      boolean metrics, List<ScheduledMethodRunnable> scheduled) {
    var context = new AnnotationConfigApplicationContext();
    context.getEnvironment().setActiveProfiles("stock-grouping-test");
    TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
        context, "cloudmall.stock.sales-metrics.enabled=" + metrics);
    var redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(redis.scan(any())).thenAnswer(invocation -> mock(Cursor.class));
    var scheduler =
        mock(
            TaskScheduler.class,
            invocation -> {
              if (invocation.getMethod().getName().equals("getClock")) return Clock.systemUTC();
              if (ScheduledFuture.class.isAssignableFrom(invocation.getMethod().getReturnType())) {
                scheduled.add((ScheduledMethodRunnable) invocation.getArgument(0));
                return mock(ScheduledFuture.class);
              }
              return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });
    context.register(Components.class, StockDependencyAspect.class);
    context.registerBean(StockSqlMapper.class, () -> mock(StockSqlMapper.class));
    context.registerBean(
        SeckillQuotaSettlementMapper.class, () -> mock(SeckillQuotaSettlementMapper.class));
    context.registerBean(
        StockConsistencyAuditStateMapper.class, () -> mock(StockConsistencyAuditStateMapper.class));
    context.registerBean(StockSalesOutboxMapper.class, () -> mock(StockSalesOutboxMapper.class));
    context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
    context.registerBean(StringRedisTemplate.class, () -> redis);
    context.registerBean(CachingConnectionFactory.class, () -> new CachingConnectionFactory());
    context.registerBean(
        RabbitTemplate.class,
        () -> new RabbitTemplate(context.getBean(CachingConnectionFactory.class)));
    context.registerBean("transactionManager", Transactions.class, Transactions::new);
    context.registerBean("taskScheduler", TaskScheduler.class, () -> scheduler);
    try {
      context.refresh();
      return context;
    } catch (RuntimeException failure) {
      context.close();
      throw failure;
    }
  }

  /** 只有本fixture激活时扫描实际业务包，避免污染应用启动。 */
  @Configuration
  @Profile("stock-grouping-test")
  @EnableAspectJAutoProxy(proxyTargetClass = true)
  @EnableTransactionManagement
  @EnableScheduling
  @ComponentScan("com.cloudmall.stock.service")
  static class Components {}

  /** 仅用于注册实际事务代理的无外部资源管理器。 */
  static class Transactions extends AbstractPlatformTransactionManager {
    /** 为调用建立独立的测试事务对象。 */
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    /** 不获取任何外部连接。 */
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    /** 完成受控提交。 */
    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    /** 完成受控回滚。 */
    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
