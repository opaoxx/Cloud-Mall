package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.config.CommonWebConfiguration;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.user.config.BalanceLedgerTableInitializer;
import com.cloudmall.user.config.BalanceTccTableInitializer;
import com.cloudmall.user.config.UserAccountInitializer;
import com.cloudmall.user.config.UserTableInitializer;
import com.cloudmall.user.controller.UserController;
import com.cloudmall.user.service.BalanceLedgerService;
import com.cloudmall.user.service.impl.AuthenticationServiceImpl;
import com.cloudmall.user.service.impl.BalanceLedgerServiceImpl;
import com.cloudmall.user.service.impl.UserAddressServiceImpl;
import com.cloudmall.user.service.impl.UserProfileServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 显式独立MySQL/Redis端口上的真实MVC、账号持久化和资金代理回归。 */
class UserAccountRefactoringIT {
  /** 本类唯一数据库的管理连接。 */
  private static JdbcTemplate administration;

  /** 已成功创建且仅由本类拥有的数据库名。 */
  private static String ownedDatabase;

  /** 本类数据库连接池。 */
  private static HikariDataSource dataSource;

  /** 实际Web、MyBatis、事务和Redis配置。 */
  private static AnnotationConfigWebApplicationContext context;

  /** 仅访问本类数据库的断言入口。 */
  private static JdbcTemplate fixture;

  /** 本类独占实验Redis的15号库；启动要求空库。 */
  private static StringRedisTemplate redis;

  /** 实际HTTP请求入口。 */
  private MockMvc mvc;

  /** 响应字段读取器。 */
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeAll
  static void startOwnedApplication() throws Exception {
    int mysqlPort = explicitPort("cloudmall.user.refactor.mysql.port");
    String server = "jdbc:mysql://127.0.0.1:" + mysqlPort + "/";
    String options =
        "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&connectTimeout=2000&socketTimeout=5000";
    administration =
        new JdbcTemplate(new DriverManagerDataSource(server + options, "root", "root"));
    String candidate = "cm_refactor_user_" + UUID.randomUUID().toString().replace("-", "");
    administration.execute(
        "CREATE DATABASE " + candidate + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    ownedDatabase = candidate;
    dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(server + ownedDatabase + options);
    dataSource.setUsername("root");
    dataSource.setPassword("root");
    dataSource.setMaximumPoolSize(4);
    dataSource.setConnectionInitSql("SET SESSION information_schema_stats_expiry=0");
    fixture = new JdbcTemplate(dataSource);
    for (String line : Files.readAllLines(Path.of("..", "database", "schema.sql"))) {
      if (line.matches("CREATE TABLE IF NOT EXISTS (mall_user|user_address|balance_ledger) .*"))
        fixture.execute(line);
    }
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.register(
        Application.class,
        UserController.class,
        AuthenticationServiceImpl.class,
        UserProfileServiceImpl.class,
        UserAddressServiceImpl.class,
        BalanceLedgerServiceImpl.class,
        UserAccountInitializer.class,
        UserTableInitializer.class,
        BalanceTccTableInitializer.class,
        BalanceLedgerTableInitializer.class);
    context.refresh();
    redis = context.getBean(StringRedisTemplate.class);
    assertEquals(Set.of(), redis.keys("*"), "Explicit experiment Redis DB15 must start empty");
    assertTrue(AopUtils.isAopProxy(context.getBean(BalanceLedgerService.class)));
    assertFalse(AopUtils.isAopProxy(context.getBean(UserController.class)));
    long routes =
        context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().values().stream()
            .filter(handler -> handler.getBeanType() == UserController.class)
            .count();
    assertEquals(13, routes);
    assertEquals(
        1,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM mall_user WHERE username='admin'", Integer.class));
    System.out.println("USER_ACCOUNT_OWNED_DATABASE=" + ownedDatabase);
  }

  @BeforeEach
  void prepareCase() {
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
    fixture.execute("DROP TRIGGER IF EXISTS reject_account_fixture_ledger");
    for (String table :
        List.of(
            "balance_ledger",
            "balance_debit_attempt",
            "balance_tcc_debit",
            "user_tcc_receive",
            "user_address")) fixture.execute("DELETE FROM " + table);
    fixture.update("DELETE FROM mall_user WHERE username<>'admin'");
  }

  @AfterEach
  void cleanCaseKeys() {
    AuthContext.clear();
    // Only remove keys created by this fixture on its explicit empty experiment database.
    if (redis != null) {
      Set<String> keys = redis.keys("*");
      assertNotNull(keys);
      assertTrue(
          keys.stream()
              .allMatch(key -> key.startsWith("auth:token:") || key.startsWith("balance:debit:")));
      if (!keys.isEmpty()) redis.delete(keys);
      assertEquals(Set.of(), redis.keys("*"));
    }
  }

  @AfterAll
  static void closeOwnedResources() {
    try {
      if (context != null) context.close();
    } finally {
      try {
        if (dataSource != null) dataSource.close();
      } finally {
        if (ownedDatabase != null) {
          if (!ownedDatabase.matches("cm_refactor_user_[a-f0-9]{32}"))
            throw new IllegalStateException("Unknown fixture database");
          administration.execute("DROP DATABASE " + ownedDatabase);
          assertEquals(
              0,
              administration.queryForObject(
                  "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                  Integer.class,
                  ownedDatabase));
        }
      }
    }
  }

  /** 注册/登录/资料/登出保持数字身份、字段、金额、BCrypt和真实Token的TTL。 */
  @Test
  void accountLifecyclePreservesJsonAndRedisContract() throws Exception {
    long userId = register("account-flow");
    Map<String, Object> row = fixture.queryForMap("SELECT * FROM mall_user WHERE id=?", userId);
    assertTrue(
        new BCryptPasswordEncoder().matches("Fixture123!", (String) row.get("password_hash")));
    JsonNode login =
        data(json(post("/api/auth/login"), credentials("account-flow")).andExpect(status().isOk()));
    String token = login.get("token").textValue();
    assertEquals(2, login.size());
    assertEquals(userId, login.path("user").path("userId").longValue());
    assertEquals("10000.00", login.path("user").path("balance").textValue());
    assertEquals("", login.path("user").path("nickname").textValue());
    assertEquals(userId + ":USER", redis.opsForValue().get("auth:token:" + token));
    Long remaining = redis.getExpire("auth:token:" + token, TimeUnit.SECONDS);
    assertNotNull(remaining);
    assertTrue(remaining > 7100 && remaining <= 7200);
    mvc.perform(get("/api/users/me").header("X-User-Id", userId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.roles[0]").value("USER"));
    json(
            put("/api/users/me").header("X-User-Id", userId),
            "{\"nickname\":\"reader\",\"phone\":null,\"avatarUrl\":null}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.nickname").value("reader"))
        .andExpect(jsonPath("$.data.phone").value(""));
    assertNull(
        fixture
            .queryForMap("SELECT phone,avatar_url FROM mall_user WHERE id=?", userId)
            .get("phone"));
    mvc.perform(post("/api/auth/logout").header("Authorization", "bearer " + token))
        .andExpect(status().isOk());
    assertTrue(Boolean.TRUE.equals(redis.hasKey("auth:token:" + token)));
    mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer  " + token + "  "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").doesNotExist());
    assertFalse(Boolean.TRUE.equals(redis.hasKey("auth:token:" + token)));
    mvc.perform(post("/api/auth/logout")).andExpect(status().isOk());
    assertFalse(AuthContext.authenticated());
  }

  /** 绑定校验仍早于业务鉴权，禁用账号及错误密码均不能创建Token。 */
  @Test
  void validationAndAuthenticationFailureOrderIsStable() throws Exception {
    long userId = register("validation-flow");
    json(post("/api/auth/register"), credentials("validation-flow"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USER_DUPLICATE_USERNAME"));
    json(post("/api/auth/login"), "{\"username\":\"validation-flow\",\"password\":\"wrong\"}")
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("USER_LOGIN_FAILED"));
    fixture.update("UPDATE mall_user SET status=0 WHERE id=?", userId);
    json(post("/api/auth/login"), credentials("validation-flow"))
        .andExpect(status().isUnauthorized());
    json(post("/api/auth/login"), "{\"username\":\"\",\"password\":\"\"}")
        .andExpect(status().isBadRequest());
    json(post("/api/users/me/addresses"), "{}").andExpect(status().isBadRequest());
    json(post("/api/users/me/addresses"), address(false)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/users/me").header("X-User-Id", 99999999L))
        .andExpect(status().isNotFound());
    assertEquals(Set.of(), redis.keys("*"));
  }

  /** 全部地址路由覆盖默认排序、更新后清理及跨用户零修改。 */
  @Test
  void addressCrudPreservesOwnershipDefaultsAndNumericIds() throws Exception {
    long owner = register("address-owner");
    long other = register("address-other");
    JsonNode first =
        data(
            json(post("/api/users/me/addresses").header("X-User-Id", owner), address(true))
                .andExpect(status().isOk()));
    assertTrue(first.path("id").isIntegralNumber());
    long firstId = first.path("id").longValue();
    long secondId =
        data(json(post("/api/users/me/addresses").header("X-User-Id", owner), address(true))
                .andExpect(status().isOk()))
            .path("id")
            .longValue();
    JsonNode listed =
        data(
            mvc.perform(get("/api/users/me/addresses").header("X-User-Id", owner))
                .andExpect(status().isOk()));
    assertEquals(secondId, listed.get(0).path("id").longValue());
    assertFalse(listed.get(1).path("isDefault").booleanValue());
    List<Map<String, Object>> before =
        fixture.queryForList("SELECT * FROM user_address ORDER BY id");
    json(put("/api/users/me/addresses/" + firstId).header("X-User-Id", other), address(true))
        .andExpect(status().isNotFound());
    mvc.perform(delete("/api/users/me/addresses/" + firstId).header("X-User-Id", other))
        .andExpect(status().isNotFound());
    assertEquals(before, fixture.queryForList("SELECT * FROM user_address ORDER BY id"));
    json(put("/api/users/me/addresses/" + firstId).header("X-User-Id", owner), address(true))
        .andExpect(status().isOk());
    assertEquals(
        firstId,
        fixture.queryForObject(
            "SELECT id FROM user_address WHERE user_id=? AND is_default=1", Long.class, owner));
    mvc.perform(delete("/api/users/me/addresses/" + firstId).header("X-User-Id", owner))
        .andExpect(status().isOk());
    mvc.perform(delete("/api/users/me/addresses/" + firstId).header("X-User-Id", owner))
        .andExpect(status().isNotFound());
  }

  /** HTTP委托命中真实Service事务代理，拒绝结果提交，数据库异常回滚余额和归属。 */
  @Test
  void balanceRoutesRetainCommitRollbackReplayAndRefundSemantics() throws Exception {
    long userId = register("balance-flow");
    String root = "/api/internal/users/" + userId + "/balance";
    String debit =
        "{\"operationKey\":\"account-pay\",\"payNo\":\"account-pay\",\"orderNo\":\"account-order\",\"amount\":\"10.00\"}";
    json(post(root + "/debits").header("X-User-Id", userId), debit)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.balance").value("9990.00"));
    json(post(root + "/debits").header("X-User-Id", userId), debit).andExpect(status().isOk());
    assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM balance_ledger", Integer.class));
    String refund =
        "{\"refundOperationKey\":\"account-refund\",\"originalOperationKey\":\"account-pay\",\"payNo\":\"account-pay\",\"orderNo\":\"account-order\",\"amount\":\"10.00\"}";
    json(post(root + "/refunds").header("X-User-Id", userId), refund)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.balance").value("10000.00"));
    mvc.perform(get(root + "/operations/account-pay").header("X-User-Id", userId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.refundOperationKey").value("account-refund"));
    json(
            post(root + "/debit").header("X-User-Id", userId),
            "{\"paymentKey\":\"account-rejected\",\"amount\":\"20000.00\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_FAILED"));
    assertEquals(
        "REJECTED",
        fixture.queryForObject(
            "SELECT state FROM balance_ledger WHERE operation_key='account-rejected'",
            String.class));
    fixture.execute(
        "CREATE TRIGGER reject_account_fixture_ledger BEFORE INSERT ON balance_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned ledger fault'");
    try {
      json(
              post(root + "/debit").header("X-User-Id", userId),
              "{\"paymentKey\":\"account-rollback\",\"amount\":\"1.00\"}")
          .andExpect(status().isInternalServerError());
      assertEquals(
          "10000.00",
          fixture
              .queryForObject(
                  "SELECT balance FROM mall_user WHERE id=?", java.math.BigDecimal.class, userId)
              .toPlainString());
      assertEquals(
          0,
          fixture.queryForObject(
              "SELECT COUNT(*) FROM balance_debit_attempt WHERE pay_no='account-rollback'",
              Integer.class));
      assertFalse(
          Boolean.TRUE.equals(redis.hasKey("balance:debit:" + userId + ":account-rollback")));
    } finally {
      fixture.execute("DROP TRIGGER reject_account_fixture_ledger");
    }
  }

  private long register(String username) throws Exception {
    JsonNode registered =
        data(json(post("/api/auth/register"), credentials(username)).andExpect(status().isOk()));
    assertEquals(2, registered.size());
    assertTrue(registered.path("userId").isIntegralNumber());
    assertEquals(username, registered.path("username").textValue());
    return registered.path("userId").longValue();
  }

  private static String credentials(String username) {
    return "{\"username\":\"" + username + "\",\"password\":\"Fixture123!\"}";
  }

  private static String address(boolean defaultAddress) {
    return "{\"receiver\":\"reader\",\"phone\":\"13800000000\",\"detailAddress\":\"fixture road\",\"isDefault\":"
        + defaultAddress
        + "}";
  }

  private ResultActions json(MockHttpServletRequestBuilder request, String body) throws Exception {
    return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private JsonNode data(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
  }

  private static int explicitPort(String property) {
    int port = Integer.parseInt(Objects.requireNonNull(System.getProperty(property), property));
    if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
    return port;
  }

  /** 使用生产初始化链、服务、Mapper、拦截器和异常映射，不启动Nacos或Seata。 */
  @Configuration
  @EnableWebMvc
  @EnableTransactionManagement(proxyTargetClass = true)
  @MapperScan("com.cloudmall.user.mapper")
  @Import({CommonWebConfiguration.class, GlobalExceptionHandler.class})
  static class Application {
    @Bean(destroyMethod = "")
    DataSource dataSource() {
      return dataSource;
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource source) {
      return new DataSourceTransactionManager(source);
    }

    @Bean
    MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource source) {
      MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
      factory.setDataSource(source);
      MybatisConfiguration configuration = new MybatisConfiguration();
      configuration.setMapUnderscoreToCamelCase(true);
      factory.setConfiguration(configuration);
      return factory;
    }

    @Bean
    LettuceConnectionFactory redisConnectionFactory() {
      RedisStandaloneConfiguration configuration =
          new RedisStandaloneConfiguration(
              "127.0.0.1", explicitPort("cloudmall.user.refactor.redis.port"));
      configuration.setPassword("root");
      configuration.setDatabase(15);
      return new LettuceConnectionFactory(configuration);
    }

    @Bean
    StringRedisTemplate redis(LettuceConnectionFactory factory) {
      return new StringRedisTemplate(factory);
    }
  }
}
