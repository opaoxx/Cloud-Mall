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
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.config.StockDependencyAspect;
import com.cloudmall.stock.controller.StockController;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillAcceptanceService;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementService;
import com.cloudmall.stock.service.impl.SeckillRequestClaimService;
import com.cloudmall.stock.service.impl.StockReservationRequestService;
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

/** 使用真实继承 Controller、Spring AOP 和 transaction advisor 验证事务边界。 */
class StockDependencyTransactionTest {
  @AfterEach
  void clearRules() {
    DegradeRuleManager.loadRules(java.util.List.of());
    ClusterBuilderSlot.resetClusterNodes();
  }

  @Test
  void inheritedControllerAndCompatibilityCallKeepGuardThroughCommit() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(Beans.class)) {
      StockController controller = context.getBean(StockController.class);
      TransactionProbe transactions = context.getBean(TransactionProbe.class);
      assertTrue(AopUtils.isAopProxy(controller));

      controller.confirm("order-test");

      assertEquals(1, transactions.begins);
      assertEquals(1, transactions.commits);
      assertTrue(transactions.guardedAtBegin);
      assertTrue(transactions.guardedAtCommit);
      assertFalse(StockDependencyGuard.mysqlActive());
    }
  }

  @Test
  void commitFailuresOpenCircuitBeforeAnyThirdTransactionBegins() {
    ClusterBuilderSlot.resetClusterNodes();
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(Beans.class)) {
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
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(Beans.class)) {
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

  @Configuration
  @EnableAspectJAutoProxy(proxyTargetClass = true)
  @EnableTransactionManagement
  static class Beans {
    @Bean
    StockDependencyAspect aspect() {
      return new StockDependencyAspect();
    }

    @Bean
    StockController controller() {
      return new StockController();
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
      doReturn(ApiResponse.ok(java.util.Map.of("confirmed", true)))
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
