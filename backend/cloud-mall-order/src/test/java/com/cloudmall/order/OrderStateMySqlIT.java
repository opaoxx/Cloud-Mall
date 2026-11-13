package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.order.controller.OrderStateController;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.OrderStateService;
import com.cloudmall.order.service.impl.OrderStateServiceImpl;
import com.cloudmall.order.service.impl.OrderWriteMonitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实生产状态代理/MySQL验证状态与动作原子性及confirm原无事务语义。 */
class OrderStateMySqlIT {
  @Test
  void paidCancelAndConfirmKeepTheirOriginalWritesAndReplayRules() {
    try (var fixture = new Fixture()) {
      fixture.seed(1, "202611paid", "PENDING_PAYMENT");
      fixture.seed(2, "202611cancel", "PENDING_PAYMENT");
      AuthContext.set(8L, "USER");
      assertEquals("PAID", fixture.controller.paid("202611paid").data.status);
      assertEquals("CANCELLED", fixture.controller.cancel("202611cancel", null).data.status);
      fixture.controller.paid("202611paid");
      fixture.controller.cancel("202611cancel", 8L);
      assertEquals(2, fixture.actionCount());
      assertEquals(
          "202611paid:CONFIRM",
          fixture.jdbc.queryForObject(
              "SELECT operation_key FROM order_stock_action WHERE order_no='202611paid'",
              String.class));
      assertEquals(
          "202611cancel:ROLLBACK",
          fixture.jdbc.queryForObject(
              "SELECT operation_key FROM order_stock_action WHERE order_no='202611cancel'",
              String.class));
      assertEquals("COMPLETED", fixture.controller.confirm("202611paid").data.status);
      fixture.controller.confirm("202611paid");
      assertEquals(2, fixture.actionCount());
      assertEquals(List.of(true, true, false), fixture.orders.transactionAtUpdate);
      assertEquals(
          409,
          assertThrows(BizException.class, () -> fixture.controller.paid("202611paid"))
              .getHttpStatus());
    }
  }

  @Test
  void failedActionInsertRollsBackBothPaidAndCancelledState() {
    try (var fixture = new Fixture()) {
      fixture.seed(1, "202611atomic", "PENDING_PAYMENT");
      AuthContext.set(8L, "USER");
      fixture.jdbc.execute(
          "CREATE TRIGGER reject_action BEFORE INSERT ON order_stock_action FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TEST_ACTION_INSERT_FAILED'");
      assertThrows(DataAccessException.class, () -> fixture.controller.paid("202611atomic"));
      assertEquals("PENDING_PAYMENT", fixture.status("202611atomic"));
      assertEquals(0, fixture.actionCount());
      assertThrows(DataAccessException.class, () -> fixture.controller.cancel("202611atomic", 8L));
      assertEquals("PENDING_PAYMENT", fixture.status("202611atomic"));
      assertEquals(0, fixture.actionCount());
      fixture.jdbc.execute("DROP TRIGGER reject_action");
      fixture.controller.paid("202611atomic");
      assertEquals("PAID", fixture.status("202611atomic"));
      assertEquals(1, fixture.actionCount());
      assertEquals(List.of(true, true, true), fixture.orders.transactionAtUpdate);
    }
  }

  @Test
  void confirmReadFailureDoesNotIntroduceRollbackOfItsOriginalAutocommitWrite() {
    try (var fixture = new Fixture()) {
      fixture.seed(1, "202611confirm", "PAID");
      AuthContext.set(8L, "USER");
      fixture.orders.failReadAfterCompletion = true;
      assertThrows(
          DataAccessResourceFailureException.class,
          () -> fixture.controller.confirm("202611confirm"));
      assertEquals("COMPLETED", fixture.status("202611confirm"));
      assertEquals(0, fixture.actionCount());
      assertEquals(List.of(false), fixture.orders.transactionAtUpdate);
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
  }

  /** 仅专用测试上下文激活，避免被正常服务扫描使用。 */
  @Configuration
  @Profile("order-state-mysql-it")
  @EnableTransactionManagement
  static class Transactions {}

  /** 执行原SQL，仅记录原UPDATE的事务状态并可注入更新后的读取异常。 */
  static class ObservedOrders extends OrderSqlMapper {
    /** UPDATE时实际事务状态。 */
    private final List<Boolean> transactionAtUpdate = new ArrayList<>();

    /** 是否在完成状态实际写入后模拟响应读取失败。 */
    private boolean failReadAfterCompletion;

    /** 下一次find是否抛出受控异常。 */
    private boolean rejectNextRead;

    ObservedOrders(JdbcTemplate jdbc) {
      super(jdbc);
    }

    /** 记录事务标志，保持原CAS SQL和返回值。 */
    @Override
    public int updateStatus(
        String table, String orderNo, String target, String current, Object updatedAt) {
      transactionAtUpdate.add(TransactionSynchronizationManager.isActualTransactionActive());
      int changed = super.updateStatus(table, orderNo, target, current, updatedAt);
      if (failReadAfterCompletion && "COMPLETED".equals(target)) rejectNextRead = true;
      return changed;
    }

    /** 仅指定读取抛错，不修改已经提交的数据库事实。 */
    @Override
    public List<Map<String, Object>> findOrder(String table, String orderNo) {
      if (rejectNextRead) {
        rejectNextRead = false;
        throw new DataAccessResourceFailureException("TEST_READ_AFTER_COMPLETION");
      }
      return super.findOrder(table, orderNo);
    }
  }

  /** 每例只拥有一个全新UUID库，生产类由Spring实际装配。 */
  static class Fixture implements AutoCloseable {
    /** 当前用例唯一库名。 */
    private final String schema =
        "order_state_refactor_" + UUID.randomUUID().toString().replace("-", "");

    /** 仅管理本例库生命周期的入口。 */
    private final JdbcTemplate administrator;

    /** 本例真实SQL准备与事实读取。 */
    private final JdbcTemplate jdbc;

    /** 原SQL行为的观察Mapper。 */
    private final ObservedOrders orders;

    /** 本例真实Spring事务上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 无事务HTTP层，调用实际状态代理。 */
    private final OrderStateController controller;

    Fixture() {
      String url = System.getProperty("cloudmall.mysql.test.url");
      if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_order"))
        throw new IllegalStateException("需要独立实验cloudmall_order URL");
      administrator =
          new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
      administrator.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
      try {
        var source =
            new DriverManagerDataSource(
                configured(url.replace("/cloudmall_order", "/" + schema)), "root", "root");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE mall_order_202611 LIKE cloudmall_order.mall_order_template");
        jdbc.execute(
            "CREATE TABLE mall_order_item_202611 LIKE cloudmall_order.mall_order_item_template");
        jdbc.execute("CREATE TABLE order_stock_action LIKE cloudmall_order.order_stock_action");
        orders = new ObservedOrders(jdbc);
        context.getEnvironment().setActiveProfiles("order-state-mysql-it");
        context.register(
            Transactions.class,
            OrderStateServiceImpl.class,
            OrderStateController.class,
            OrderWriteMonitor.class);
        context.registerBean(OrderSqlMapper.class, () -> orders);
        context.registerBean(OrderStockActionMapper.class, () -> new OrderStockActionMapper(jdbc));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(
            "transactionManager",
            DataSourceTransactionManager.class,
            () -> new DataSourceTransactionManager(source));
        context.refresh();
        controller = context.getBean(OrderStateController.class);
        assertTrue(AopUtils.isAopProxy(context.getBean(OrderStateService.class)));
        assertFalse(AopUtils.isAopProxy(controller));
      } catch (RuntimeException | Error failure) {
        context.close();
        administrator.execute("DROP DATABASE " + schema);
        throw failure;
      }
    }

    private void seed(long identifier, String orderNo, String status) {
      jdbc.update(
          "INSERT INTO mall_order_202611 (id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,created_at,updated_at) VALUES(?,?,8,?,12.30,12.30,'{}','2026-11-01 12:00:00.123','2026-11-01 12:00:00.123')",
          identifier,
          orderNo,
          status);
    }

    private String status(String orderNo) {
      return jdbc.queryForObject(
          "SELECT status FROM mall_order_202611 WHERE order_no=?", String.class, orderNo);
    }

    private int actionCount() {
      return jdbc.queryForObject("SELECT COUNT(*) FROM order_stock_action", Integer.class);
    }

    private static String configured(String url) {
      return url + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
    }

    /** 关闭事务上下文并仅删除本例UUID库。 */
    @Override
    public void close() {
      AuthContext.clear();
      context.close();
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      if (!schema.matches("order_state_refactor_[0-9a-f]{32}"))
        throw new IllegalStateException("未知库归属");
      administrator.execute("DROP DATABASE " + schema);
      assertEquals(
          0,
          administrator.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              schema));
    }
  }
}
