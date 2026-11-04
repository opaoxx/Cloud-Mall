package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.dto.SeataTransactionDemoDTO;
import com.cloudmall.order.domain.vo.SeataStockDemoBranchVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.SeataOrderDemoMapper;
import com.cloudmall.order.service.SeataDemoControlledRollbackException;
import com.cloudmall.order.service.SeataOrderDemoBranchService;
import com.cloudmall.order.service.SeataTransactionDemoOrchestrator;
import com.cloudmall.order.service.SeataTransactionDemoService;
import com.cloudmall.order.service.impl.seata.SeataOrderDemoBranchServiceImpl;
import io.seata.core.context.RootContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实MyBatis和本地事务；不启动Seata TC，不把本地分支事实等同于AT二阶段结论。 */
class SeataOrderDemoMySqlIT {
  @Test
  void componentNamesMappingAndLocalCommitBeforeRemoteCallRemainIntact() throws Exception {
    try (var database = new OwnedDatabase();
        var context = database.context()) {
      var branch = context.getBean(SeataOrderDemoBranchService.class);
      var entry = context.getBean(SeataTransactionDemoService.class);
      assertTrue(AopUtils.isAopProxy(branch));
      assertFalse(AopUtils.isAopProxy(entry));
      assertSame(branch, context.getBean("cm18SeataOrderBranchService"));
      assertSame(entry, context.getBean("cm18SeataDemoFacade"));
      assertSame(
          context.getBean(SeataTransactionDemoOrchestrator.class),
          context.getBean("cm18SeataDemoService"));
      var mapper = context.getBean(SeataOrderDemoMapper.class);
      assertSame(mapper, context.getBean("cm18SeataOrderDemoMapper"));
      for (String alias :
          List.of(
              "seataOrderDemoBranchServiceImpl",
              "seataTransactionDemoServiceImpl",
              "seataTransactionDemoOrchestratorImpl",
              "seataOrderDemoMapper")) assertFalse(context.containsBean(alias));
      var request = request("cm18-mapping", "NONE");
      String xid = "local-order-xid";
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(xid.getBytes(StandardCharsets.UTF_8)),
                  0,
                  8);
      when(database.stock.executeSeataDemoBranch(any(), any(), any(), any()))
          .thenAnswer(
              invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("CREATED", database.status(request.txId));
                assertEquals(xid, invocation.getArgument(1));
                var result = new SeataStockDemoBranchVO();
                result.txId = request.txId;
                result.xid = hash + "12345678";
                result.status = "WRITTEN";
                return ApiResponse.ok(result);
              });
      RootContext.bind(xid);
      try {
        assertEquals("COMMITTED", entry.execute(request).status);
        assertEquals("COMMITTED", database.status(request.txId));
        var saved = mapper.selectById(request.txId);
        assertEquals(xid, saved.txXid);
        assertEquals(0, saved.createdAt.getNano() % 1_000_000);
        assertEquals(hash, entry.execute(request).xid);
        verify(database.stock, times(1)).executeSeataDemoBranch(any(), any(), any(), any());
        assertEquals(1, database.writes.completedInserts);
        assertEquals(2, database.writes.completedConfirmations);
        assertEquals(1, database.count());
      } finally {
        RootContext.unbind();
      }
      database.assertOnlyDemoTable();
    }
  }

  @Test
  void controlledFailureStaysOutsideLocalCommitAndSkipsConfirmation() throws Exception {
    try (var database = new OwnedDatabase();
        var context = database.context()) {
      RootContext.bind("local-controlled-xid");
      try {
        assertThrows(
            SeataDemoControlledRollbackException.class,
            () ->
                context
                    .getBean(SeataTransactionDemoService.class)
                    .execute(request("cm18-controlled", "ORDER_AFTER_WRITE")));
      } finally {
        RootContext.unbind();
      }
      assertEquals("CREATED", database.status("cm18-controlled"));
      assertEquals(1, database.writes.completedInserts);
      assertEquals(0, database.writes.completedConfirmations);
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      verifyNoInteractions(database.stock);
      // 没有启动TC，本地已提交行按原语义保留；全局回滚不是此测试的结论。
      database.assertOnlyDemoTable();
    }
  }

  @Test
  void failureAfterActualInsertRollsBackLocalBranchAndSameKeyCanBeWritten() throws Exception {
    try (var database = new OwnedDatabase();
        var context = database.context()) {
      var branch = context.getBean(SeataOrderDemoBranchService.class);
      database.writes.failAfterInsert = true;
      assertThrows(RuntimeException.class, () -> branch.write("cm18-rollback", "first-xid"));
      assertEquals(1, database.writes.completedInserts);
      assertEquals(0, database.count());
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      database.writes.failAfterInsert = false;
      branch.write("cm18-rollback", "second-xid");
      assertEquals(1, database.count());
      assertEquals(
          "second-xid",
          context.getBean(SeataOrderDemoMapper.class).selectById("cm18-rollback").txXid);
      database.assertOnlyDemoTable();
    }
  }

  private static SeataTransactionDemoDTO request(String txId, String failurePoint) {
    var request = new SeataTransactionDemoDTO();
    request.txId = txId;
    request.failurePoint = failurePoint;
    return request;
  }

  /** 只在本IT显式激活，避免被真实应用扫描时替换运行配置。 */
  @Configuration
  @Profile("order-seata-refactor-mysql-it")
  @EnableTransactionManagement
  @MapperScan(basePackageClasses = SeataOrderDemoMapper.class)
  @ComponentScan(basePackageClasses = SeataOrderDemoBranchServiceImpl.class)
  static class Transactions {}

  /** 检查真实写入namespace、分支事务及提交标记的事务外边界。 */
  @Intercepts(
      @Signature(
          type = Executor.class,
          method = "update",
          args = {MappedStatement.class, Object.class}))
  static class WriteProbe implements Interceptor {
    /** 实际执行过的插入数，包括随后回滚的写入。 */
    private int completedInserts;

    /** 实际执行过的事务外确认UPDATE数。 */
    private int completedConfirmations;

    /** 在插入返回后、事务提交前注入测试异常。 */
    private boolean failAfterInsert;

    /** 在不改写SQL的情况下观察事务并按测试开关注入异常。 */
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
      String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
      boolean insert = statement.equals("com.cloudmall.order.mapper.SeataOrderDemoMapper.insert");
      assertTrue(
          insert
              || statement.equals("com.cloudmall.order.mapper.SeataOrderDemoMapper.markCommitted"));
      assertEquals(insert, TransactionSynchronizationManager.isActualTransactionActive());
      Object result = invocation.proceed();
      if (insert) {
        completedInserts++;
        if (failAfterInsert) throw new IllegalStateException("TEST_FAILURE_AFTER_INSERT");
      } else {
        completedConfirmations++;
      }
      return result;
    }
  }

  /** 每例只拥有并删除一个新UUID库，管理员连接不写入共享业务表。 */
  static class OwnedDatabase implements AutoCloseable {
    /** 当前例子的唯一数据库名。 */
    private final String schema =
        "order_seata_refactor_" + UUID.randomUUID().toString().replace("-", "");

    /** 仅负责本例库生命周期的管理员连接。 */
    private final JdbcTemplate administrator;

    /** 指向本例库的真实MySQL连接。 */
    private final DataSource dataSource;

    /** 用独立连接检查已提交事实的测试工具。 */
    private final JdbcTemplate jdbc;

    /** 实际Mapper写入探针。 */
    private final WriteProbe writes = new WriteProbe();

    /** 受控远程分支替身，不连接真实Stock。 */
    private final StockClient stock = mock(StockClient.class);

    /** 创建专属库及原演练表定义，失败时仅清理该库。 */
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
        jdbc.execute(
            "CREATE TABLE seata_cm18_order_demo (tx_id VARCHAR(64) PRIMARY KEY, "
                + "tx_xid VARCHAR(128) NOT NULL, status VARCHAR(16) NOT NULL, "
                + "created_at DATETIME(3) NOT NULL) ENGINE=InnoDB");
      } catch (RuntimeException failure) {
        administrator.execute("DROP DATABASE " + schema);
        throw failure;
      }
    }

    private AnnotationConfigApplicationContext context() throws Exception {
      var factory = new MybatisSqlSessionFactoryBean();
      factory.setDataSource(dataSource);
      factory.setPlugins(writes);
      SqlSessionFactory sessions = factory.getObject();
      var context = new AnnotationConfigApplicationContext();
      context.getEnvironment().setActiveProfiles("order-seata-refactor-mysql-it");
      context.register(Transactions.class);
      context.registerBean(DataSource.class, () -> dataSource);
      context.registerBean(SqlSessionFactory.class, () -> sessions);
      context.registerBean(StockClient.class, () -> stock);
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

    private int count() {
      return jdbc.queryForObject("SELECT COUNT(*) FROM seata_cm18_order_demo", Integer.class);
    }

    private String status(String txId) {
      return jdbc.queryForObject(
          "SELECT status FROM seata_cm18_order_demo WHERE tx_id=?", String.class, txId);
    }

    private void assertOnlyDemoTable() {
      assertEquals(
          List.of("seata_cm18_order_demo"),
          jdbc.queryForList(
              "SELECT table_name FROM information_schema.tables WHERE table_schema=?",
              String.class,
              schema));
    }

    private static String configured(String url) {
      return url + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
    }

    /** 仅删除本例核验过的UUID库，并确认删除已完成。 */
    @Override
    public void close() {
      if (!schema.matches("order_seata_refactor_[0-9a-f]{32}"))
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
