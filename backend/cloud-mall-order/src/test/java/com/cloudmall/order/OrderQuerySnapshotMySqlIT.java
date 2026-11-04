package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.order.controller.OrderQueryController;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.OrderQueryService;
import com.cloudmall.order.service.impl.OrderQueryServiceImpl;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 实际查询Service代理在COUNT与PAGE之间遭遇并发提交时仍保持同一只读RR快照。 */
class OrderQuerySnapshotMySqlIT {
  @Test
  void concurrentStatusChangeAndInsertsDoNotMixCountAndPageSnapshots() throws Exception {
    try (var database = new OwnedDatabase()) {
      var executor = Executors.newSingleThreadExecutor();
      try {
        database.mapper.afterFirstCount =
            () -> {
              try {
                executor
                    .submit(
                        () -> {
                          var transaction =
                              new TransactionTemplate(
                                  new DataSourceTransactionManager(database.dataSource));
                          transaction.executeWithoutResult(
                              status -> {
                                database.jdbc.update(
                                    "UPDATE mall_order_202608 SET status='CANCELLED' WHERE order_no='202608old'");
                                database.insert(2, "202608new-a", 7L);
                                database.insert(4, "202608new-b", 7L);
                              });
                          assertFalse(
                              TransactionSynchronizationManager.isActualTransactionActive());
                        })
                    .get(10, TimeUnit.SECONDS);
              } catch (Exception failure) {
                throw new IllegalStateException("并发写入未完成", failure);
              }
            };
        try (var context = database.context()) {
          var controller = context.getBean(OrderQueryController.class);
          assertFalse(AopUtils.isAopProxy(controller));
          assertTrue(AopUtils.isAopProxy(context.getBean(OrderQueryService.class)));
          AuthContext.set(7L, "USER");
          try {
            var first =
                controller.list(
                        "PAID", 1, 20, "2026-08-01T00:00:00+08:00", "2026-09-01T00:00:00+08:00")
                    .data;
            assertEquals(1, first.total);
            assertEquals(
                List.of("202608old"), first.items.stream().map(item -> item.orderNo).toList());
            assertEquals("PAID", first.items.get(0).status);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(
                "CANCELLED",
                database.jdbc.queryForObject(
                    "SELECT status FROM mall_order_202608 WHERE order_no='202608old'",
                    String.class));
            var second =
                controller.list(
                        "PAID", 1, 20, "2026-08-01T00:00:00+08:00", "2026-09-01T00:00:00+08:00")
                    .data;
            assertEquals(2, second.total);
            assertEquals(
                List.of("202608new-b", "202608new-a"),
                second.items.stream().map(item -> item.orderNo).toList());
            assertEquals(4, database.mapper.connections.size());
            assertEquals(database.mapper.connections.get(0), database.mapper.connections.get(1));
            assertEquals(database.mapper.connections.get(2), database.mapper.connections.get(3));
          } finally {
            AuthContext.clear();
          }
        }
      } finally {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      }
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertEquals(
          List.of("mall_order_202608"), database.jdbc.queryForList("SHOW TABLES", String.class));
    }
  }

  /** 仅本IT激活，避免真实服务扫描载入测试配置。 */
  @Configuration
  @Profile("order-query-snapshot-it")
  @EnableTransactionManagement
  static class Transactions {}

  /** 调用原SQL，仅观察实际事务/连接并在第一次COUNT后允许独立写线程提交。 */
  static class SnapshotMapper extends OrderSqlMapper {
    /** 当前用例专属数据源。 */
    private final DataSource source;

    /** 从真实连接读取的MySQL会话标识。 */
    private final List<Long> connections = new ArrayList<>();

    /** 限制并发写入只在第一次COUNT之后触发。 */
    private final AtomicBoolean changed = new AtomicBoolean();

    /** 用例提供的独立事务写入。 */
    private Runnable afterFirstCount;

    /** 创建不替换SQL行为的观测Mapper。 */
    SnapshotMapper(DataSource source) {
      super(new JdbcTemplate(source));
      this.source = source;
    }

    /** 保持原COUNT，读取成功后才运行并发提交。 */
    @Override
    public long countOrders(String table, long userId, Object start, Object end, String status) {
      observeTransaction();
      long count = super.countOrders(table, userId, start, end, status);
      if (changed.compareAndSet(false, true)) afterFirstCount.run();
      return count;
    }

    /** 保持原分页SQL和参数，不重新开启事务。 */
    @Override
    public List<Map<String, Object>> findOrderPage(
        String table,
        long userId,
        Object start,
        Object end,
        String status,
        long offset,
        int limit) {
      observeTransaction();
      return super.findOrderPage(table, userId, start, end, status, offset, limit);
    }

    private void observeTransaction() {
      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
      assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
      assertEquals(
          Connection.TRANSACTION_REPEATABLE_READ,
          TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
      Connection connection = DataSourceUtils.getConnection(source);
      try {
        assertFalse(connection.getAutoCommit());
        assertTrue(connection.isReadOnly());
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
        try (var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
          assertTrue(rows.next());
          connections.add(rows.getLong(1));
        }
      } catch (java.sql.SQLException failure) {
        throw new IllegalStateException("无法读取测试会话", failure);
      } finally {
        DataSourceUtils.releaseConnection(connection, source);
      }
    }
  }

  /** 只克隆原模板定义到一个新UUID库，所有写入和删除均限定于该库。 */
  static class OwnedDatabase implements AutoCloseable {
    /** 当前例子唯一拥有的库名。 */
    private final String schema =
        "order_query_refactor_" + UUID.randomUUID().toString().replace("-", "");

    /** 仅控制本例UUID库生命周期。 */
    private final JdbcTemplate administrator;

    /** 本例真实MySQL数据源。 */
    private final DataSource dataSource;

    /** 准备及读取本例事实的测试工具。 */
    private final JdbcTemplate jdbc;

    /** 保留原查询SQL的观测Mapper。 */
    private final SnapshotMapper mapper;

    /** 创建专属空库并克隆模板，不复制来源库数据。 */
    OwnedDatabase() {
      String url = System.getProperty("cloudmall.mysql.test.url");
      if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_order"))
        throw new IllegalStateException("必须显式指定独立实验的cloudmall_order URL，不带查询参数");
      administrator =
          new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
      administrator.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
      try {
        dataSource =
            new DriverManagerDataSource(
                configured(url.replace("/cloudmall_order", "/" + schema)), "root", "root");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE mall_order_202608 LIKE cloudmall_order.mall_order_template");
        insert(1, "202608old", 7L);
        insert(3, "202608other-user", 8L);
        mapper = new SnapshotMapper(dataSource);
      } catch (RuntimeException failure) {
        administrator.execute("DROP DATABASE " + schema);
        throw failure;
      }
    }

    private void insert(long identifier, String orderNo, long userId) {
      jdbc.update(
          "INSERT INTO mall_order_202608 (id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,created_at,updated_at)"
              + " VALUES (?,?,?,'PAID',12.30,12.30,'{}','2026-08-15 12:00:00.123','2026-08-15 12:00:00.123')",
          identifier,
          orderNo,
          userId);
    }

    private AnnotationConfigApplicationContext context() {
      var context = new AnnotationConfigApplicationContext();
      context.getEnvironment().setActiveProfiles("order-query-snapshot-it");
      context.register(Transactions.class, OrderQueryServiceImpl.class, OrderQueryController.class);
      context.registerBean(DataSource.class, () -> dataSource);
      context.registerBean(OrderSqlMapper.class, () -> mapper);
      context.registerBean(
          "transactionManager",
          DataSourceTransactionManager.class,
          () -> new DataSourceTransactionManager(dataSource));
      try {
        context.refresh();
        return context;
      } catch (RuntimeException failure) {
        context.close();
        throw failure;
      }
    }

    private static String configured(String url) {
      return url + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
    }

    /** 只删除本例UUID库并核对实际不存在。 */
    @Override
    public void close() {
      if (!schema.matches("order_query_refactor_[0-9a-f]{32}"))
        throw new IllegalStateException("库归属未知");
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
