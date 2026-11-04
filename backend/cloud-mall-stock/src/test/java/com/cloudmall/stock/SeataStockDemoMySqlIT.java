package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.config.SeataStockDemoConfiguration;
import com.cloudmall.stock.config.StockDependencyAspect;
import com.cloudmall.stock.domain.dto.SeataStockBranchDTO;
import com.cloudmall.stock.mapper.SeataStockDemoMapper;
import com.cloudmall.stock.service.SeataStockDemoOrchestrator;
import com.cloudmall.stock.service.SeataStockDemoService;
import com.cloudmall.stock.util.StockDependencyGuard;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实MyBatis扫描、表映射和本地事务验证；不启用TC，不将本地结果冒充AT二阶段验收。 */
class SeataStockDemoMySqlIT {
  @Test
  void renamedMapperUsesOriginalTableAndControlledFailureOccursAfterCommit() throws Exception {
    try (var database = new OwnedDatabase();
        var context = database.context()) {
      var mapper = context.getBean(SeataStockDemoMapper.class);
      assertSame(mapper, context.getBean("cm18SeataStockDemoMapper"));
      assertFalse(context.containsBean("seataStockDemoMapper"));
      assertTrue(AopUtils.isAopProxy(context.getBean(SeataStockDemoService.class)));
      var orchestrator = context.getBean(SeataStockDemoOrchestrator.class);
      assertFalse(AopUtils.isAopProxy(orchestrator));

      var result = orchestrator.execute(request("cm18-mapping"), "local-xid-success", "NONE");
      assertEquals("WRITTEN", result.getStatus());
      var saved = mapper.selectById("cm18-mapping");
      assertEquals("cm18-mapping", saved.getTxId());
      assertEquals("local-xid-success", saved.getTxXid());
      assertEquals("CREATED", saved.getMarker());
      assertNotNull(saved.getCreatedAt());
      assertEquals(0, saved.getCreatedAt().getNano() % 1_000_000);

      BizException failure =
          assertThrows(
              BizException.class,
              () ->
                  orchestrator.execute(
                      request("cm18-after-write"), "local-xid-failure", "STOCK_AFTER_WRITE"));
      assertEquals("CM18_CONTROLLED_ROLLBACK", failure.getCode());
      assertEquals(409, failure.getHttpStatus());
      // 通过独立连接读取，证明分支已提交；全局回滚仍由TC负责，本测试未启动TC。
      assertEquals(2, database.count());
      assertEquals(
          "local-xid-failure",
          database.jdbc.queryForObject(
              "SELECT tx_xid FROM seata_cm18_stock_demo WHERE tx_id='cm18-after-write'",
              String.class));
      assertEquals(2, database.writes.completedWrites);
      database.assertOnlyDemoTable();
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
  }

  @Test
  void failureAfterActualInsertRollsBackTheLocalBranch() throws Exception {
    try (var database = new OwnedDatabase();
        var context = database.context()) {
      database.writes.failAfterInsert = true;
      assertThrows(
          RuntimeException.class,
          () ->
              context
                  .getBean(SeataStockDemoService.class)
                  .writeBranch(request("cm18-local-rollback"), "local-xid-rollback"));
      assertEquals(1, database.writes.completedWrites);
      assertEquals(0, database.count());
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertFalse(StockDependencyGuard.mysqlActive());
      database.writes.failAfterInsert = false;
      context
          .getBean(SeataStockDemoService.class)
          .writeBranch(request("cm18-local-rollback"), "local-xid-retry");
      assertEquals(1, database.count());
      database.assertOnlyDemoTable();
    }
  }

  private static SeataStockBranchDTO request(String txId) {
    var request = new SeataStockBranchDTO();
    request.setTxId(txId);
    return request;
  }

  /** 仅当前IT显式激活，避免污染真实服务的组件扫描。 */
  @Configuration
  @Profile("stock-seata-refactor-mysql-it")
  @EnableAspectJAutoProxy(proxyTargetClass = true)
  @EnableTransactionManagement
  @MapperScan(basePackageClasses = SeataStockDemoMapper.class)
  static class Transactions {}

  /** 插入执行后再抛错，验证真实代理可回滚已完成的SQL，并检查namespace和保护边界。 */
  @Intercepts(
      @Signature(
          type = Executor.class,
          method = "update",
          args = {MappedStatement.class, Object.class}))
  static class WriteProbe implements Interceptor {
    /** 本例实际完成SQL执行的次数。 */
    private int completedWrites;

    /** 仅在本例指定时，在SQL返回后抛错。 */
    private boolean failAfterInsert;

    /** 检查新Mapper namespace，并保留原SQL后执行可控异常。 */
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
      var statement = (MappedStatement) invocation.getArgs()[0];
      assertEquals("com.cloudmall.stock.mapper.SeataStockDemoMapper.insert", statement.getId());
      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
      assertFalse(StockDependencyGuard.mysqlActive());
      Object result = invocation.proceed();
      completedWrites++;
      if (failAfterInsert) throw new IllegalStateException("TEST_FAILURE_AFTER_ACTUAL_INSERT");
      return result;
    }
  }

  /** 每例只创建并删除自己的UUID库，源库仅作为管理员连接入口。 */
  static class OwnedDatabase implements AutoCloseable {
    /** 测试显式指定的本地实验连接。 */
    private final JdbcTemplate administrator;

    /** 本例专属库名。 */
    private final String schema =
        "stock_seata_refactor_" + UUID.randomUUID().toString().replace("-", "");

    /** 指向本例库的真实数据源。 */
    private final DataSource dataSource;

    /** 仅用于测试准备和独立连接断言。 */
    private final JdbcTemplate jdbc;

    /** 本例MyBatis写入探针。 */
    private final WriteProbe writes = new WriteProbe();

    /** 创建唯一空库及既有演练表定义，失败时只清理本例库。 */
    OwnedDatabase() {
      String url = System.getProperty("cloudmall.mysql.test.url");
      if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_stock"))
        throw new IllegalStateException("显式提供独立实验的localhost cloudmall_stock URL，不带查询参数");
      administrator =
          new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
      administrator.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
      try {
        dataSource =
            new DriverManagerDataSource(
                configured(url.replace("/cloudmall_stock", "/" + schema)), "root", "root");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
            "CREATE TABLE seata_cm18_stock_demo (tx_id VARCHAR(64) PRIMARY KEY, "
                + "tx_xid VARCHAR(128) NOT NULL, marker VARCHAR(32) NOT NULL, "
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
      context.getEnvironment().setActiveProfiles("stock-seata-refactor-mysql-it");
      TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
          context, "cloudmall.seata.cm18.enabled=true");
      context.register(
          Transactions.class, SeataStockDemoConfiguration.class, StockDependencyAspect.class);
      context.registerBean(DataSource.class, () -> dataSource);
      context.registerBean(SqlSessionFactory.class, () -> sessions);
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
      return jdbc.queryForObject("SELECT COUNT(*) FROM seata_cm18_stock_demo", Integer.class);
    }

    private void assertOnlyDemoTable() {
      assertEquals(
          java.util.List.of("seata_cm18_stock_demo"),
          jdbc.queryForList(
              "SELECT table_name FROM information_schema.tables WHERE table_schema=?",
              String.class,
              schema));
    }

    private static String configured(String url) {
      return url + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
    }

    /** 关闭时验证库名归属，只清理当前UUID库；不删除共享表或历史数据。 */
    @Override
    public void close() {
      if (!schema.matches("stock_seata_refactor_[0-9a-f]{32}"))
        throw new IllegalStateException("不允许清理非本例库");
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
