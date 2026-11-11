package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.order.controller.OrderDetailQueryController;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.impl.OrderDetailQueryServiceImpl;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 原GET无事务，写入口共享Reader则沿真实外层事务；两种情况均通过原Mapper访问MySQL。 */
class OrderDetailReadMySqlIT {
  @Test
  void sharedReaderKeepsStandaloneReadsAndExistingPaymentTransactionBoundaries() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_order"))
      throw new IllegalStateException("需要独立实验的cloudmall_order URL");
    var source = new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
    String schema = "order_detail_refactor_" + UUID.randomUUID().toString().replace("-", "");
    source.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
    try {
      var dataSource =
          new DriverManagerDataSource(
              configured(url.replace("/cloudmall_order", "/" + schema)), "root", "root");
      var jdbc = new JdbcTemplate(dataSource);
      jdbc.execute("CREATE TABLE mall_order_202610 LIKE cloudmall_order.mall_order_template");
      jdbc.execute(
          "CREATE TABLE mall_order_item_202610 LIKE cloudmall_order.mall_order_item_template");
      jdbc.execute("CREATE TABLE order_stock_action LIKE cloudmall_order.order_stock_action");
      jdbc.update(
          "INSERT INTO mall_order_202610 (id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,created_at,updated_at)"
              + " VALUES(1,'202610shared',8,'PENDING_PAYMENT',12.30,12.30,'{}','2026-10-01 12:00:00.123','2026-10-01 12:00:00.123')");
      var orders = new ObservedOrders(jdbc);
      var actions = new OrderStockActionMapper(jdbc);
      var stock = mock(StockClient.class);
      var json = new ObjectMapper();
      var query =
          new OrderDetailQueryController(
              new OrderDetailQueryServiceImpl(orders, json, actions, stock));
      AuthContext.set(8L, "USER");
      assertEquals("PENDING_PAYMENT", query.get("202610shared").data.status);
      assertEquals(List.of(false, false), orders.active);
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());

      orders.active.clear();
      orders.connections.clear();
      orders.expectTransaction = true;
      var target =
          new OrderServiceImpl(
              stock,
              mock(ProductClient.class),
              mock(UserClient.class),
              mock(CartClient.class),
              orders,
              json);
      ReflectionTestUtils.setField(target, "orderStockActionMapper", actions);
      var factory = new ProxyFactory(target);
      factory.setProxyTargetClass(true);
      factory.addAdvice(
          new TransactionInterceptor(
              new DataSourceTransactionManager(dataSource),
              new AnnotationTransactionAttributeSource()));
      var writeService = (OrderServiceImpl) factory.getProxy();
      writeService.paid("202610shared");
      assertEquals(List.of(true, true, true, true), orders.active);
      assertEquals(1, orders.connections.stream().distinct().count());
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertEquals(
          "PAID",
          jdbc.queryForObject(
              "SELECT status FROM mall_order_202610 WHERE order_no='202610shared'", String.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM order_stock_action WHERE order_no='202610shared' AND action_type='CONFIRM'",
              Integer.class));
      orders.expectTransaction = false;
      assertEquals("PENDING", query.paymentState("202610shared", 8L).data.stockActionStatus);
      assertEquals(
          0, jdbc.queryForObject("SELECT COUNT(*) FROM mall_order_item_202610", Integer.class));
    } finally {
      AuthContext.clear();
      if (!schema.matches("order_detail_refactor_[0-9a-f]{32}"))
        throw new IllegalStateException("库归属未知");
      source.execute("DROP DATABASE " + schema);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              schema));
    }
  }

  private static String configured(String url) {
    return url + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
  }

  /** 只观察实际Spring事务与绑定连接，不改变原Mapper的SQL或结果。 */
  static class ObservedOrders extends OrderSqlMapper {
    /** 与生产Mapper共用的数据访问工具。 */
    private final JdbcTemplate jdbc;

    /** 原主表/明细查询发生时的事务标志。 */
    private final List<Boolean> active = new ArrayList<>();

    /** 原读调用所属的MySQL连接ID。 */
    private final List<Long> connections = new ArrayList<>();

    /** 当前调用场景是否来自已开启的写事务。 */
    private boolean expectTransaction;

    ObservedOrders(JdbcTemplate jdbc) {
      super(jdbc);
      this.jdbc = jdbc;
    }

    /** 主表查询前观测原调用上下文。 */
    @Override
    public List<Map<String, Object>> findOrder(String table, String orderNo) {
      observe();
      return super.findOrder(table, orderNo);
    }

    /** 明细查询仍使用同一被选Mapper。 */
    @Override
    public List<Map<String, Object>> findOrderItems(String table, String orderNo) {
      observe();
      return super.findOrderItems(table, orderNo);
    }

    private void observe() {
      boolean transaction = TransactionSynchronizationManager.isActualTransactionActive();
      assertEquals(expectTransaction, transaction);
      active.add(transaction);
      if (transaction)
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
      connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
    }
  }
}
