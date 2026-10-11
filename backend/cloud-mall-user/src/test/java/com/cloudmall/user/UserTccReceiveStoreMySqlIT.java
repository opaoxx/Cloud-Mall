package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.user.controller.UserTccController;
import com.cloudmall.user.domain.dto.UserTccTryDTO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.impl.UserTccHttpService;
import com.cloudmall.user.service.impl.UserTccReceiveStore;
import com.cloudmall.user.service.impl.UserTccStateQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 一个owned接收表验证实际REQUIRES_NEW元数据事务；不运行RM/TC/资金或应用。 */
class UserTccReceiveStoreMySqlIT {
  /** 原库只用于全表fingerprint及本测试owned库管理。 */
  private static JdbcTemplate source;

  /** 原库完整表清单/checksum。 */
  private static List<Map<String, Object>> fingerprint;

  /** 唯一owned库名，DROP前严格核验。 */
  private static String database;

  /** 准备/核验owned行的独立连接入口。 */
  private static JdbcTemplate fixture;

  /** 被测连接追踪，不包装source连接。 */
  private static TrackingDataSource tracked;

  /** 实际元数据事务管理器。 */
  private static DataSourceTransactionManager manager;

  /** 实际Mapper和Spring事务代理。 */
  private static UserTccReceiveStore store;

  /** 标准原表复制到owned；只在owned模拟遗留nullable金额。 */
  @BeforeAll
  static void setup() throws Exception {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_user(\\?.*)?"))
      throw new IllegalStateException("需要显式cloudmall_user测试URL");
    source = new JdbcTemplate(new DriverManagerDataSource(shanghai(url), "root", "root"));
    fingerprint = originalFingerprint();
    database = "cm16t17_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    DataSource owned =
        new DriverManagerDataSource(
            shanghai(url.replace("/cloudmall_user", "/" + database)), "root", "root");
    fixture = new JdbcTemplate(owned);
    fixture.execute(
        (String) source.queryForMap("SHOW CREATE TABLE user_tcc_receive").get("Create Table"));
    fixture.execute("ALTER TABLE user_tcc_receive MODIFY amount DECIMAL(18,2) NULL");
    tracked = new TrackingDataSource(owned);
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(UserTccReceiveMapper.class);
    MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(tracked);
    factory.setConfiguration(configuration);
    SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
    manager = new DataSourceTransactionManager(tracked);
    ProxyFactory proxy =
        new ProxyFactory(new UserTccReceiveStore(session.getMapper(UserTccReceiveMapper.class)));
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    store = (UserTccReceiveStore) proxy.getProxy();
  }

  /** 每个测试从空owned接收表开始；不改变原库或业务数据。 */
  @BeforeEach
  void reset() {
    tracked.checking = false;
    fixture.update("DELETE FROM user_tcc_receive");
    tracked.reset();
    RootContext.unbind();
  }

  /** 无论断言结果均清理owned库，并验证source未变。 */
  @AfterAll
  static void cleanup() {
    if (tracked != null) tracked.checking = false;
    try {
      if (source != null) assertEquals(fingerprint, originalFingerprint());
      System.out.println("CM16T17 original User tables/checksums unchanged");
    } finally {
      if (source != null && database != null && database.matches("cm16t17_[0-9a-f]{32}")) {
        source.execute("DROP DATABASE " + database);
        assertEquals(
            0,
            source.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,
                database));
        System.out.println("CM16T17 fixture dropped: " + database);
      }
      RootContext.unbind();
    }
  }

  /** 真实HTTP合法请求遇到旧NULL为409，原行不变且不派发/绑定全局事务。 */
  @Test
  void legacyNullReplayThroughOriginalHttpNeverRegistersResource() throws Exception {
    seed(null);
    var before = facts();
    var resource = mock(BalanceTccDebitLocalResource.class);
    var query = mock(UserTccStateQuery.class);
    var users = mock(UserMapper.class);
    when(users.selectById(7L)).thenReturn(new UserPO());
    var bridge = new UserTccHttpService(resource, store, query, users, "ops-secret");
    var mvc =
        MockMvcBuilders.standaloneSetup(new UserTccController(bridge))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    tracked.checking = true;
    mvc.perform(
            post("/api/internal/users/7/balance/tcc-debits/try")
                .header("X-User-Id", "7")
                .header("X-Internal-Service", "cloud-mall-pay")
                .header("X-Internal-Callback-Token", "ops-secret")
                .header("TX_XID", "private-xid")
                .contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(request())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    assertEquals(2, tracked.statements.size());
    assertEquals(1, tracked.connections);
    assertTrue(tracked.statements.get(0).startsWith("INSERT IGNORE"));
    assertTrue(tracked.statements.get(1).contains("FOR UPDATE"));
    assertEquals(before, facts());
    verifyNoInteractions(resource, query);
    verify(users).selectById(7L);
    assertNull(RootContext.getXID());
  }

  /** CI候选确实匹配但错误支付别名不能提交branch/time变更。 */
  @Test
  void aliasBindingRollsBackOriginalBranchAndTimestamp() {
    seed("10.00");
    var before = facts();
    assertEquals(
        1,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM user_tcc_receive WHERE pay_no='PAY1'", Integer.class));
    tracked.checking = true;
    assertConflict(() -> store.bindBranch("PAY1", "private-xid", 11L));
    assertEquals(2, tracked.statements.size());
    assertEquals(1, tracked.connections);
    assertTrue(tracked.statements.get(0).startsWith("UPDATE"));
    assertTrue(tracked.statements.get(1).contains("FOR UPDATE"));
    assertEquals(before, facts());
  }

  /** 非正branch零SQL；事务代理建连接不等于允许写入，null原noop保持。 */
  @Test
  void nonpositiveAndNullBranchLeaveAllFactsUntouched() {
    seed("10.00");
    var before = facts();
    for (Long branchId : new Long[] {0L, -1L}) {
      tracked.reset();
      tracked.checking = true;
      assertConflict(() -> store.bindBranch("pay1", "private-xid", branchId));
      assertTrue(tracked.statements.isEmpty());
      assertEquals(before, facts());
    }
    tracked.reset();
    store.bindBranch("pay1", "private-xid", null);
    assertTrue(tracked.statements.isEmpty());
    assertEquals(before, facts());
  }

  /** 初次claim独立提交不随外层回滚，重放/绑定幂等和错误rebind保持。 */
  @Test
  void winnerRequiresNewAndBindingIdempotencyRemainStable() {
    tracked.checking = true;
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              assertTrue(store.claim(7L, request()));
              status.setRollbackOnly();
            });
    assertEquals(1, facts().size());
    assertEquals(2, tracked.statements.size());
    assertEquals(2, tracked.connections);
    var before = facts();
    tracked.reset();
    UserTccTryDTO replay = request();
    replay.amount = new BigDecimal("10.000");
    assertFalse(store.claim(7L, replay));
    assertEquals(before, facts());
    assertEquals(2, tracked.statements.size());
    tracked.reset();
    store.bindBranch("pay1", "private-xid", 11L);
    assertEquals(11L, ((Number) facts().get(0).get("branch_id")).longValue());
    assertEquals(2, tracked.statements.size());
    before = facts();
    tracked.reset();
    store.bindBranch("pay1", "private-xid", 11L);
    assertEquals(before, facts());
    assertEquals(2, tracked.statements.size());
    tracked.reset();
    assertConflict(() -> store.bindBranch("pay1", "private-xid", 12L));
    assertEquals(before, facts());
    assertEquals(2, tracked.statements.size());
  }

  /** 固定幂等冲突不授权第二次派发，不输出底层错误。 */
  private static void assertConflict(Runnable action) {
    BizException failure = assertThrows(BizException.class, action::run);
    assertEquals(409, failure.getHttpStatus());
    assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
  }

  /** 原HTTP载荷由合法canonical字段组成。 */
  private static UserTccTryDTO request() {
    UserTccTryDTO request = new UserTccTryDTO();
    request.payNo = "pay1";
    request.orderNo = "order1";
    request.xid = "private-xid";
    request.amount = new BigDecimal("10.00");
    request.operationKey = "tcc-debit:pay1";
    request.ledgerKey = "pay1";
    return request;
  }

  /** 仅owned表准备旧接收行，NULL金额原样保留。 */
  private static void seed(String amount) {
    fixture.update(
        "INSERT INTO"
            + " user_tcc_receive(id,pay_no,xid,branch_id,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at)"
            + " VALUES(1,'pay1','private-xid',NULL,7,'order1',?,'tcc-debit:pay1','pay1','CLAIMED','2026-10-11','2026-10-11')",
        amount);
  }

  /** 全部原字段包括主键/branch/时间；从独立连接观察已提交事实。 */
  private static List<Map<String, Object>> facts() {
    return fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id");
  }

  /** 原所有User表只读校验和，不写原库。 */
  private static List<Map<String, Object>> originalFingerprint() {
    List<Map<String, Object>> values =
        new ArrayList<>(source.queryForList("SHOW TABLES FROM cloudmall_user"));
    for (String table : source.queryForList("SHOW TABLES FROM cloudmall_user", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法原表");
      values.addAll(source.queryForList("CHECKSUM TABLE cloudmall_user." + table));
    }
    return values;
  }

  /** 项目上海时间语义，不改全局数据库设置。 */
  private static String shanghai(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
  }

  /** 只允许被测接收表原SQL，确认每条SQL均有实际可写事务。 */
  private static final class TrackingDataSource extends AbstractDataSource {
    /** owned基础连接入口。 */
    private final DataSource delegate;

    /** 只在被测入口启用SQL/事务核验。 */
    private boolean checking;

    /** 实际取得连接数量。 */
    private int connections;

    /** 实际准备的原元数据SQL。 */
    private final List<String> statements = new ArrayList<>();

    /** 创建owned事务监控，不获取连接。 */
    TrackingDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    /** 每个场景独立计数。 */
    void reset() {
      connections = 0;
      statements.clear();
    }

    /** 被测原SQL必须在非autoCommit的可写事务中，不允许触碰资金/Fence表。 */
    @Override
    public Connection getConnection() throws java.sql.SQLException {
      Connection connection = delegate.getConnection();
      if (checking) connections++;
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                if (checking && "prepareStatement".equals(method.getName())) {
                  String sql = ((String) arguments[0]).stripLeading().toUpperCase(Locale.ROOT);
                  assertTrue(
                      sql.startsWith("INSERT IGNORE INTO USER_TCC_RECEIVE")
                          || sql.startsWith("UPDATE USER_TCC_RECEIVE")
                          || sql.startsWith("SELECT * FROM USER_TCC_RECEIVE"),
                      sql);
                  assertFalse(connection.getAutoCommit());
                  assertFalse(connection.isReadOnly());
                  statements.add(sql);
                }
                try {
                  return method.invoke(connection, arguments);
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }

    /** 固定owned凭证连接，不分叉到其他库。 */
    @Override
    public Connection getConnection(String username, String password) throws java.sql.SQLException {
      return getConnection();
    }
  }
}
