package com.cloudmall.stock;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.config.StockDependencyAspect;
import com.cloudmall.stock.controller.StockController;
import com.cloudmall.stock.domain.vo.StockActionResultVO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.StockReservationRequestService;
import com.cloudmall.stock.service.impl.seckill.SeckillAcceptanceService;
import com.cloudmall.stock.service.impl.seckill.SeckillQuotaSettlementService;
import com.cloudmall.stock.service.impl.seckill.SeckillRequestClaimService;
import com.cloudmall.stock.util.StockDependencyGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 使用组合 Controller、Spring AOP 和 transaction advisor 验证事务边界。 */
class StockDependencyTransactionTest {
  @AfterEach
  void clearRules() {
    DegradeRuleManager.loadRules(java.util.List.of());
    ClusterBuilderSlot.resetClusterNodes();
  }

  @Test
  void composedControllerAndCompatibilityCallKeepGuardThroughCommit() {
    try (AnnotationConfigApplicationContext context = context()) {
      StockController controller = context.getBean(StockController.class);
      TransactionProbe transactions = context.getBean(TransactionProbe.class);
      assertFalse(AopUtils.isAopProxy(controller));
      assertTrue(
          AopUtils.isAopProxy(
              context.getBean(com.cloudmall.stock.service.StockReservationService.class)));

      context
          .getBean(com.cloudmall.stock.service.StockReservationService.class)
          .confirm("order-test");

      assertEquals(1, transactions.begins);
      assertEquals(1, transactions.commits);
      assertTrue(transactions.guardedAtBegin);
      assertTrue(transactions.guardedAtCommit);
      assertFalse(StockDependencyGuard.mysqlActive());
    }
  }

  @Test
  void commitFailureThroughControllerCannotProduceSuccessEnvelope() throws Exception {
    try (var context = context()) {
      context.getBean(TransactionProbe.class).failCommit = true;
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                  context.getBean(StockController.class))
              .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
              .build();
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                  "/api/stock/reservations/order-test/confirm"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isServiceUnavailable())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                  .value("STOCK_SERVICE_UNAVAILABLE"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data")
                  .value(org.hamcrest.Matchers.nullValue()));
      assertFalse(StockDependencyGuard.mysqlActive());
    }
  }

  @Test
  void queryGuardWrapsOnlyPersistentStatusAndLeavesCacheHitOutsideMysql() {
    try (AnnotationConfigApplicationContext context = context()) {
      var query = context.getBean(com.cloudmall.stock.service.StockQueryService.class);
      var stocks = context.getBean(StockSqlMapper.class);
      when(stocks.findReservationRequest("read-order")).thenReturn(null);
      when(stocks.findSeckillByOrderNo("read-order"))
          .thenAnswer(
              invocation -> {
                assertTrue(StockDependencyGuard.mysqlActive());
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return null;
              });
      assertEquals("NOT_FOUND", query.reservation("read-order").status);
      assertFalse(StockDependencyGuard.mysqlActive());
      var redis = context.getBean(StringRedisTemplate.class);
      org.springframework.data.redis.core.HashOperations<String, Object, Object> hash =
          mock(org.springframework.data.redis.core.HashOperations.class);
      when(redis.type("stock:available:7"))
          .thenReturn(org.springframework.data.redis.connection.DataType.HASH);
      when(redis.opsForHash()).thenReturn(hash);
      when(hash.get("stock:available:7", "quantity"))
          .thenAnswer(
              invocation -> {
                assertFalse(StockDependencyGuard.mysqlActive());
                return "12";
              });
      assertEquals(12, query.get(7L).availableQuantity);
      org.mockito.Mockito.verify(stocks, org.mockito.Mockito.never()).findStock(7L);
    }
  }

  @Test
  void commitFailuresOpenCircuitBeforeAnyThirdTransactionBegins() {
    ClusterBuilderSlot.resetClusterNodes();
    try (AnnotationConfigApplicationContext context = context()) {
      StockController controller = context.getBean(StockController.class);
      TransactionProbe transactions = context.getBean(TransactionProbe.class);
      transactions.failCommit = true;
      // 使用实际默认统计窗口，避免上下文启动或冷调用耗时让失败样本过期。
      DegradeRuleManager.loadRules(
          java.util.List.of(
              new DegradeRule(StockDependencyGuard.MYSQL_TRANSACTION)
                  .setGrade(1)
                  .setCount(0.5)
                  .setMinRequestAmount(2)
                  .setStatIntervalMs(60000)
                  .setTimeWindow(10)));

      for (int request = 0; request < 3; request++) {
        BizException failure =
            assertThrows(BizException.class, () -> controller.confirm("order-test", null));
        assertEquals("STOCK_SERVICE_UNAVAILABLE", failure.getCode());
      }

      assertEquals(2, transactions.begins);
      assertTrue(transactions.guardedAtCommit);
      assertFalse(StockDependencyGuard.mysqlActive());
    }
  }

  @Test
  void businessConflictRollsBackInsideGuardWithoutOpeningDatabaseCircuit() {
    try (AnnotationConfigApplicationContext context = context()) {
      when(context.getBean(SeckillQuotaSettlementService.class).settle(anyString(), anyString()))
          .thenThrow(new BizException("SECKILL_SETTLEMENT_CONFLICT", "conflict", 409));
      StockController controller = context.getBean(StockController.class);
      TransactionProbe transactions = context.getBean(TransactionProbe.class);

      assertThrows(BizException.class, () -> controller.rollback("order-test", null));

      assertEquals(1, transactions.rollbacks);
      assertTrue(transactions.guardedAtRollback);
      assertFalse(StockDependencyGuard.mysqlActive());
    }
  }

  /** 测试配置只在当前显式上下文激活，避免真实Stock启动时自动扫描到夹具。 */
  private static AnnotationConfigApplicationContext context() {
    var context = new AnnotationConfigApplicationContext();
    context.getEnvironment().setActiveProfiles("stock-dependency-test");
    context.register(Beans.class);
    try {
      context.refresh();
      return context;
    } catch (RuntimeException failure) {
      context.close();
      throw failure;
    }
  }

  @Configuration
  @org.springframework.context.annotation.Profile("stock-dependency-test")
  @EnableAspectJAutoProxy(proxyTargetClass = true)
  @EnableTransactionManagement
  @org.springframework.context.annotation.Import({
    com.cloudmall.stock.service.impl.StockReservationServiceImpl.class,
    com.cloudmall.stock.service.impl.StockQueryServiceImpl.class,
    com.cloudmall.stock.service.impl.StockReservationReader.class,
    com.cloudmall.stock.service.impl.StockReservationSnapshotCodec.class,
    com.cloudmall.stock.service.impl.seckill.SeckillReservationServiceImpl.class
  })
  static class Beans {
    @Bean
    StockDependencyAspect aspect() {
      return new StockDependencyAspect();
    }

    @Bean
    StockController controller(
        com.cloudmall.stock.service.StockQueryService query,
        com.cloudmall.stock.service.StockReservationService reservations,
        com.cloudmall.stock.service.SeckillReservationService seckill) {
      return new StockController(query, reservations, seckill);
    }

    @Bean
    StockSqlMapper stockMapper() {
      return mock(StockSqlMapper.class);
    }

    @Bean
    SeckillQuotaSettlementMapper quotaMapper() {
      return mock(SeckillQuotaSettlementMapper.class);
    }

    @Bean
    StringRedisTemplate redis() {
      return mock(StringRedisTemplate.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    RabbitTemplate rabbit() {
      return mock(RabbitTemplate.class);
    }

    @Bean
    StockReservationRequestService normalClaim() {
      return mock(StockReservationRequestService.class);
    }

    @Bean
    SeckillRequestClaimService seckillClaim() {
      return mock(SeckillRequestClaimService.class);
    }

    @Bean
    SeckillAcceptanceService acceptance() {
      return mock(SeckillAcceptanceService.class);
    }

    @Bean
    TransactionProbe transactionManager() {
      return new TransactionProbe();
    }

    @Bean
    SeckillQuotaSettlementService settlement() {
      SeckillQuotaSettlementService settlement = mock(SeckillQuotaSettlementService.class);
      doReturn(StockActionResultVO.confirmed(false))
          .when(settlement)
          .settle(anyString(), anyString());
      return settlement;
    }
  }

  /** 真正 transaction advisor 使用的探针，检查 begin/commit/rollback 时 guard 仍存活。 */
  static class TransactionProbe extends AbstractPlatformTransactionManager {
    private int begins;
    private int commits;
    private int rollbacks;
    private boolean failCommit;
    private boolean guardedAtBegin;
    private boolean guardedAtCommit;
    private boolean guardedAtRollback;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
      return TransactionSynchronizationManager.isActualTransactionActive();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      begins++;
      guardedAtBegin = StockDependencyGuard.mysqlActive();
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
      guardedAtCommit = StockDependencyGuard.mysqlActive();
      if (failCommit) throw new TransactionSystemException("commit acknowledgement lost");
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
      guardedAtRollback = StockDependencyGuard.mysqlActive();
    }
  }
}
