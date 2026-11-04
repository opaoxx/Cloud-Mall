package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.dto.AddressRequestDTO;
import com.cloudmall.user.domain.dto.CredentialsDTO;
import com.cloudmall.user.domain.po.UserAddressPO;
import com.cloudmall.user.domain.po.UserPO;
import com.cloudmall.user.domain.vo.UserAddressVO;
import com.cloudmall.user.domain.vo.UserVO;
import com.cloudmall.user.mapper.UserAddressMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 仅独立cm_user_ids_*库的真实MyBatis自增、并发和迁移验证，不写默认User业务表。 */
class UserGeneratedIdentifierMySqlIT {
  /** 被复制旧行的代表性13位用户主键。 */
  private static final long LEGACY_USER = 1793000000000L;

  /** 被复制旧行的代表性13位地址主键。 */
  private static final long LEGACY_ADDRESS = 1793000727572L;

  /** 现有JSON数字契约上限，本测试只进行有限新增。 */
  private static final long MAX_SAFE_ID = 9007199254740991L;

  /** 原库连接仅用于只读校验和及隔离库创建/删除。 */
  private static JdbcTemplate source;

  /** 显式提供的原库URL，不作为写入目标。 */
  private static String sourceUrl;

  /** 原两表的行数、MAX和内容校验和。 */
  private static List<Map<String, Object>> originalFacts;

  /** 本方法唯一的隔离库名。 */
  private String database;

  /** 被测Mapper的独立物理连接来源。 */
  private DataSource owned;

  /** 串行fixture和元数据后验专用连接。 */
  private SingleConnectionDataSource fixtureSource;

  /** 只在owned库准备数据和核验结果。 */
  private JdbcTemplate fixture;

  /** 读取原两表摘要，未显式指定测试URL时拒绝此IT。 */
  @BeforeAll
  static void readOriginalFacts() {
    sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    if (sourceUrl == null || !sourceUrl.matches("jdbc:mysql://[^/]+/cloudmall_user(\\?.*)?"))
      throw new IllegalStateException("显式IT需要cloudmall_user的cloudmall.mysql.test.url");
    source = new JdbcTemplate(new DriverManagerDataSource(configured(sourceUrl), "root", "root"));
    originalFacts = sourceFacts();
  }

  /** 创建唯一隔离库，每个方法自己选择旧表迁移或新库DDL，不复用业务表数据。 */
  @BeforeEach
  void createOwnedDatabase() {
    database = "cm_user_ids_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    String ownedUrl = configured(sourceUrl.replace("/cloudmall_user", "/" + database));
    owned = new DriverManagerDataSource(ownedUrl, "root", "root");
    fixtureSource = new SingleConnectionDataSource(ownedUrl, "root", "root", true);
    fixture = new JdbcTemplate(fixtureSource);
    fixture.execute("SET SESSION information_schema_stats_expiry=0");
  }

  /** 只清理本方法的owned库；即使断言失败仍保原两表不变的核验。 */
  @AfterEach
  void cleanupOwnedDatabase() {
    AuthContext.clear();
    try {
      assertEquals(originalFacts, sourceFacts());
    } finally {
      if (fixtureSource != null) fixtureSource.destroy();
      if (database != null && database.matches("cm_user_ids_[0-9a-f]{32}")) {
        source.execute("DROP DATABASE " + database);
        assertEquals(
            0,
            source.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,
                database));
      }
    }
  }

  /** 实际原库行数、MAX和checksum必须在所有定向测试前后保持。 */
  @AfterAll
  static void verifyOriginalFacts() {
    assertEquals(originalFacts, sourceFacts());
    System.out.println("USER_ID_IT default mall_user/user_address rows and checksums unchanged");
  }

  /** 旧ID迁移两次幂等，两独立factory并发注册/地址及同毫秒持久对象均不碰撞。 */
  @Test
  void migrationAndConcurrentGeneratedKeysPreserveLegacyAndJson() throws Exception {
    createLegacyTables();
    List<Map<String, Object>> legacyUsers = fixture.queryForList("SELECT * FROM mall_user");
    List<Map<String, Object>> legacyAddresses = fixture.queryForList("SELECT * FROM user_address");
    List<Map<String, Object>> indexes = indexDefinitions();
    migrate();
    long userNext = next("mall_user");
    long addressNext = next("user_address");
    migrate();
    assertEquals(userNext, next("mall_user"));
    assertEquals(addressNext, next("user_address"));
    assertEquals(legacyUsers, fixture.queryForList("SELECT * FROM mall_user"));
    assertEquals(legacyAddresses, fixture.queryForList("SELECT * FROM user_address"));
    assertEquals(indexes, indexDefinitions());
    assertTrue(userNext > LEGACY_USER && addressNext > LEGACY_ADDRESS);

    SqlSessionTemplate first = mapperSession();
    SqlSessionTemplate second = mapperSession();
    List<UserAccountTestFixture> services = List.of(service(first), service(second));
    List<Long> users =
        parallel(
            16,
            index ->
                identifier(
                    services.get(index % 2).controller().register(credentials("parallel_" + index)),
                    "userId"));
    List<Long> addresses =
        parallel(
            16,
            index -> {
              AuthContext.set(users.get(index), "USER");
              try {
                return ((UserAddressVO)
                        services.get(index % 2).controller().addAddress(addressRequest()).data)
                    .id;
              } finally {
                AuthContext.clear();
              }
            });
    assertGenerated(users, LEGACY_USER);
    assertGenerated(addresses, LEGACY_ADDRESS);

    // 同一个固定毫秒的PO不参与分配主键，两个独立Mapper仍从数据库得到各自唯一值。
    LocalDateTime sameMillisecond = LocalDateTime.of(2026, 10, 26, 12, 0, 0, 123000000);
    List<Long> fixedUsers =
        parallel(
            8,
            index -> {
              UserPO row = user("same_millisecond_" + index);
              row.createdAt = sameMillisecond;
              row.updatedAt = sameMillisecond;
              UserMapper mapper = (index % 2 == 0 ? first : second).getMapper(UserMapper.class);
              assertNull(row.id);
              assertEquals(1, mapper.insert(row));
              return row.id;
            });
    List<Long> fixedAddresses =
        parallel(
            8,
            index -> {
              UserAddressPO row = address(fixedUsers.get(index));
              row.createdAt = sameMillisecond;
              row.updatedAt = sameMillisecond;
              UserAddressMapper mapper =
                  (index % 2 == 0 ? first : second).getMapper(UserAddressMapper.class);
              assertNull(row.id);
              assertEquals(1, mapper.insert(row));
              return row.id;
            });
    List<Long> allUsers = new ArrayList<>(users);
    allUsers.addAll(fixedUsers);
    List<Long> allAddresses = new ArrayList<>(addresses);
    allAddresses.addAll(fixedAddresses);
    assertGenerated(allUsers, LEGACY_USER);
    assertGenerated(allAddresses, LEGACY_ADDRESS);
    assertEquals(25, fixture.queryForObject("SELECT COUNT(*) FROM mall_user", Integer.class));
    assertEquals(25, fixture.queryForObject("SELECT COUNT(*) FROM user_address", Integer.class));
    for (Long identity : allUsers) {
      assertEquals(
          identity,
          first.getMapper(UserMapper.class).selectById(identity).id,
          "generated key must equal the persisted identity");
      var json =
          new ObjectMapper()
              .valueToTree(UserVO.from(first.getMapper(UserMapper.class).selectById(identity)));
      assertTrue(json.get("userId").isIntegralNumber());
      assertEquals(identity.longValue(), (long) json.get("userId").doubleValue());
    }
    for (Long identity : allAddresses) {
      UserAddressPO stored = first.getMapper(UserAddressMapper.class).selectById(identity);
      assertEquals(identity, stored.id);
      assertTrue(
          new ObjectMapper().valueToTree(UserAddressVO.from(stored)).get("id").isIntegralNumber());
    }

    long oldMaximum = fixture.queryForObject("SELECT MAX(id) FROM mall_user", Long.class);
    UserAccountTestFixture restarted = service(mapperSession());
    long restartedIdentity =
        identifier(restarted.controller().register(credentials("after_restart")), "userId");
    assertTrue(restartedIdentity > oldMaximum && restartedIdentity < MAX_SAFE_ID);
    assertThrows(
        BizException.class, () -> restarted.controller().register(credentials("after_restart")));
    UserPO duplicate = user("after_restart");
    assertThrows(
        DuplicateKeyException.class, () -> first.getMapper(UserMapper.class).insert(duplicate));

    AuthContext.set(users.get(0), "USER");
    UserAddressVO updated =
        (UserAddressVO)
            restarted.controller().updateAddress(addresses.get(0), addressRequest()).data;
    assertEquals(addresses.get(0), updated.id);
    assertEquals(25, fixture.queryForObject("SELECT COUNT(*) FROM user_address", Integer.class));
    assertEquals(
        legacyUsers, fixture.queryForList("SELECT * FROM mall_user WHERE id=?", LEGACY_USER));
    assertEquals(
        legacyAddresses,
        fixture.queryForList("SELECT * FROM user_address WHERE id=?", LEGACY_ADDRESS));
    System.out.println(
        "USER_ID_IT migrated old identities; two factories; 24 concurrent users/addresses; restart"
            + " and JSON numeric PASS");
  }

  /** 地址更新必须在同一SQL中核对归属，拒绝时不改他人地址或调用者默认地址。 */
  @Test
  void addressUpdateCannotTransferAnotherUsersAddress() throws Exception {
    for (String line : Files.readAllLines(Path.of("..", "database", "schema.sql"))) {
      if (line.startsWith("CREATE TABLE IF NOT EXISTS mall_user ")
          || line.startsWith("CREATE TABLE IF NOT EXISTS user_address ")) fixture.execute(line);
    }
    UserAccountTestFixture service = service(mapperSession());
    AddressRequestDTO request = addressRequest();
    request.isDefault = true;
    AuthContext.set(7L, "USER");
    UserAddressVO original = (UserAddressVO) service.controller().addAddress(request).data;
    AuthContext.set(8L, "USER");
    UserAddressVO callersDefault = (UserAddressVO) service.controller().addAddress(request).data;
    List<Map<String, Object>> before =
        fixture.queryForList("SELECT * FROM user_address ORDER BY id");

    BizException forbidden =
        assertThrows(
            BizException.class, () -> service.controller().updateAddress(original.id, request));
    assertEquals(404, forbidden.getHttpStatus());
    assertEquals(com.cloudmall.common.error.ErrorCodes.NOT_FOUND, forbidden.getCode());
    assertEquals(before, fixture.queryForList("SELECT * FROM user_address ORDER BY id"));
    assertTrue(
        ((UserAddressVO) service.controller().updateAddress(callersDefault.id, request).data)
            .isDefault);

    AuthContext.set(7L, "USER");
    assertEquals(
        original.id,
        ((UserAddressVO) service.controller().updateAddress(original.id, request).data).id);
    assertEquals(
        7L,
        fixture.queryForObject(
            "SELECT user_id FROM user_address WHERE id=?", Long.class, original.id));
  }

  /** 新库DDL直接支持生成键；无admin时自动分配，有admin时不创建第二条。 */
  @Test
  void newSchemaAndBootstrapUseGeneratedKeys() throws Exception {
    for (String line : Files.readAllLines(Path.of("..", "database", "schema.sql"))) {
      if (line.startsWith("CREATE TABLE IF NOT EXISTS mall_user ")
          || line.startsWith("CREATE TABLE IF NOT EXISTS user_address ")) fixture.execute(line);
    }
    UserAccountTestFixture service = service(mapperSession());
    service.initializer().ensureLocalAdmin();
    Map<String, Object> before =
        fixture.queryForMap("SELECT * FROM mall_user WHERE username='admin'");
    service(mapperSession()).initializer().ensureLocalAdmin();
    assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM mall_user", Integer.class));
    assertEquals(before, fixture.queryForMap("SELECT * FROM mall_user WHERE username='admin'"));
    assertTrue(((Number) before.get("id")).longValue() > 0);
    assertTrue(((Number) before.get("id")).longValue() < MAX_SAFE_ID);
  }

  /** 未迁移的真实表不能被新User初始化器写入或自动修改。 */
  @Test
  void unmigratedSchemaFailsBeforeBootstrap() throws Exception {
    createLegacyTables();
    UserAccountTestFixture service = service(mapperSession());
    BizException error = assertThrows(BizException.class, service.initializer()::ensureLocalAdmin);
    assertEquals("USER_IDENTIFIER_SCHEMA_INCOMPATIBLE", error.getCode());
    assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM mall_user", Integer.class));
    assertEquals(
        "",
        fixture.queryForObject(
            "SELECT extra FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                + " table_name='mall_user' AND column_name='id'",
            String.class));
  }

  /** 元数据或精度前置不符必须在两表任一ALTER之前停止。 */
  @Test
  void migrationRejectsCompositePrimaryKeyWithoutChangingFirstTable() throws Exception {
    createLegacyTables();
    fixture.execute(
        "ALTER TABLE user_address ADD COLUMN fixture_part INT NOT NULL DEFAULT 0, DROP PRIMARY KEY,"
            + " ADD PRIMARY KEY(id,fixture_part)");
    assertThrows(Exception.class, this::migrate);
    assertEquals(
        "",
        fixture.queryForObject(
            "SELECT extra FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                + " table_name='mall_user' AND column_name='id'",
            String.class));
    assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM mall_user", Integer.class));
  }

  /** 范围不足的旧行阻止迁移，已自增表的unsafe next只触发只读启动拒绝。 */
  @Test
  void unsafeMaximumOrNextIsRejectedWithoutUnsafeInsert() throws Exception {
    createLegacyTables();
    fixture.update("UPDATE mall_user SET id=? WHERE id=?", MAX_SAFE_ID, LEGACY_USER);
    assertThrows(Exception.class, this::migrate);
    assertEquals(
        "",
        fixture.queryForObject(
            "SELECT extra FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                + " table_name='mall_user' AND column_name='id'",
            String.class));
    fixture.update("UPDATE mall_user SET id=? WHERE id=?", LEGACY_USER, MAX_SAFE_ID);
    migrate();
    fixture.execute("ALTER TABLE mall_user AUTO_INCREMENT=" + MAX_SAFE_ID);
    int before = fixture.queryForObject("SELECT COUNT(*) FROM mall_user", Integer.class);
    assertThrows(BizException.class, service(mapperSession()).initializer()::ensureLocalAdmin);
    assertEquals(before, fixture.queryForObject("SELECT COUNT(*) FROM mall_user", Integer.class));
  }

  /** 预热旧NULL后迁移，从真实生产YAML绑定的池使每条新连接都读实时元数据。 */
  @Test
  void productionHikariInitializationAvoidsPreMigrationMetadataCache() throws Exception {
    createLegacyTables();
    try (Connection defaultConnection = owned.getConnection()) {
      long defaultExpiry =
          queryLong(defaultConnection, "SELECT @@SESSION.information_schema_stats_expiry");
      long globalBefore =
          queryLong(defaultConnection, "SELECT @@GLOBAL.information_schema_stats_expiry");
      assertEquals(86400, defaultExpiry);
      assertNull(metadataNext(defaultConnection, "mall_user"));
      assertNull(metadataNext(defaultConnection, "user_address"));
      migrate();
      Long cachedUserNext = metadataNext(defaultConnection, "mall_user");
      Long cachedAddressNext = metadataNext(defaultConnection, "user_address");
      boolean staleNullReproduced = cachedUserNext == null || cachedAddressNext == null;
      if (staleNullReproduced) {
        SingleConnectionDataSource cachedSource =
            new SingleConnectionDataSource(defaultConnection, true);
        assertThrows(
            BizException.class,
            service(mapperSession(cachedSource)).initializer()::ensureLocalAdmin);
      }
      long expectedUserNext = next("mall_user");
      long expectedAddressNext = next("user_address");
      assertTrue(expectedUserNext > LEGACY_USER && expectedUserNext < MAX_SAFE_ID);
      assertTrue(expectedAddressNext > LEGACY_ADDRESS && expectedAddressNext < MAX_SAFE_ID);

      java.util.Set<Long> physicalConnections = new HashSet<>();
      try (HikariDataSource pool = productionPool();
          Connection first = pool.getConnection();
          Connection second = pool.getConnection()) {
        physicalConnections.add(queryLong(first, "SELECT CONNECTION_ID()"));
        physicalConnections.add(queryLong(second, "SELECT CONNECTION_ID()"));
        assertEquals(2, physicalConnections.size());
        for (Connection connection : List.of(first, second)) {
          assertEquals(
              0, queryLong(connection, "SELECT @@SESSION.information_schema_stats_expiry"));
          assertEquals(expectedUserNext, metadataNext(connection, "mall_user"));
          assertEquals(expectedAddressNext, metadataNext(connection, "user_address"));
        }
      }
      try (HikariDataSource restartedPool = productionPool();
          Connection restarted = restartedPool.getConnection()) {
        assertTrue(physicalConnections.add(queryLong(restarted, "SELECT CONNECTION_ID()")));
        assertEquals(0, queryLong(restarted, "SELECT @@SESSION.information_schema_stats_expiry"));
        UserAccountTestFixture initialized = service(mapperSession(restartedPool));
        initialized.initializer().ensureLocalAdmin();
        initialized.initializer().ensureLocalAdmin();
        assertEquals(2, fixture.queryForObject("SELECT COUNT(*) FROM mall_user", Integer.class));
      }
      assertEquals(
          defaultExpiry,
          queryLong(defaultConnection, "SELECT @@SESSION.information_schema_stats_expiry"));
      assertEquals(
          globalBefore,
          queryLong(defaultConnection, "SELECT @@GLOBAL.information_schema_stats_expiry"));
      System.out.println(
          "USER_ID_METADATA_CACHE warmDefault="
              + defaultExpiry
              + " staleNullReproduced="
              + staleNullReproduced
              + " productionYamlPhysicalConnections="
              + physicalConnections.size()
              + " poolRecreated=true globalUnchanged=true startupPassed=true");
    }
  }

  /** 加载实际User资源并用Boot同款Binder绑定Hikari；只覆盖URL为owned及小池预算。 */
  private HikariDataSource productionPool() throws Exception {
    MutablePropertySources properties = new MutablePropertySources();
    for (var property :
        new YamlPropertySourceLoader()
            .load("production-user", new ClassPathResource("application.yml")))
      properties.addLast(property);
    Binder binder = new Binder(ConfigurationPropertySources.from(properties));
    DataSourceProperties datasource =
        binder.bind("spring.datasource", Bindable.of(DataSourceProperties.class)).get();
    String url = configured(sourceUrl.replace("/cloudmall_user", "/" + database));
    assertFalse(url.toLowerCase(java.util.Locale.ROOT).contains("sessionvariables"));
    assertFalse(url.contains("information_schema_stats_expiry"));
    datasource.setUrl(url);
    HikariDataSource pool =
        datasource.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    binder.bind("spring.datasource.hikari", Bindable.ofInstance(pool));
    assertEquals("SET SESSION information_schema_stats_expiry=0", pool.getConnectionInitSql());
    pool.setMaximumPoolSize(2);
    pool.setMinimumIdle(0);
    pool.setConnectionTimeout(5000);
    pool.setInitializationFailTimeout(5000);
    return pool;
  }

  private static long queryLong(Connection connection, String query) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(query)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static Long metadataNext(Connection connection, String table) throws Exception {
    assertTrue(List.of("mall_user", "user_address").contains(table));
    try (var statement =
        connection.prepareStatement(
            "SELECT auto_increment FROM information_schema.tables"
                + " WHERE table_schema=DATABASE() AND table_name=?")) {
      statement.setString(1, table);
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        long value = rows.getLong(1);
        return rows.wasNull() ? null : value;
      }
    }
  }

  /** 仅复制两张原表定义，旧13位行均为本测试自有fixture。 */
  private void createLegacyTables() {
    for (String table : List.of("mall_user", "user_address")) {
      String definition =
          (String) source.queryForMap("SHOW CREATE TABLE " + table).get("Create Table");
      // 默认库现在可能已迁移，创建前去掉自增声明，不能先建AUTO表又缓存其next。
      definition =
          definition
              .replaceAll("(?i)\\s+AUTO_INCREMENT=[0-9]+", "")
              .replaceAll("(?i)\\s+AUTO_INCREMENT\\b", "");
      fixture.execute(definition);
    }
    fixture.update(
        "INSERT INTO"
            + " mall_user(id,username,password_hash,role,balance,frozen_balance,status,created_at,updated_at)"
            + " VALUES(?,?,?,'USER',10000,0,1,NOW(3),NOW(3))",
        LEGACY_USER,
        "legacy-user",
        "legacy-hash");
    fixture.update(
        "INSERT INTO"
            + " user_address(id,user_id,receiver_name,receiver_phone,region_detail,is_default,created_at,updated_at)"
            + " VALUES(?,?,'legacy','13800000000','legacy',0,NOW(3),NOW(3))",
        LEGACY_ADDRESS,
        LEGACY_USER);
  }

  /** 执行本仓库真实迁移文件，DELIMITER只用于划分受控过程而不发给服务器。 */
  private void migrate() throws Exception {
    String delimiter = ";";
    StringBuilder statement = new StringBuilder();
    try (Connection connection = owned.getConnection()) {
      for (String line :
          Files.readAllLines(Path.of("..", "database", "migrate_user_generated_ids.sql"))) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
        if (trimmed.startsWith("DELIMITER ")) {
          assertTrue(statement.toString().isBlank());
          delimiter = trimmed.substring("DELIMITER ".length());
          continue;
        }
        statement.append(line).append('\n');
        String pending = statement.toString().stripTrailing();
        if (pending.endsWith(delimiter)) {
          try (Statement sql = connection.createStatement()) {
            sql.execute(pending.substring(0, pending.length() - delimiter.length()));
          }
          statement.setLength(0);
        }
      }
      assertTrue(statement.toString().isBlank());
    }
  }

  private SqlSessionTemplate mapperSession() throws Exception {
    return mapperSession(owned);
  }

  private SqlSessionTemplate mapperSession(DataSource datasource) throws Exception {
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.setLogImpl(NoLoggingImpl.class);
    configuration.addMapper(UserMapper.class);
    configuration.addMapper(UserAddressMapper.class);
    MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(datasource);
    factory.setConfiguration(configuration);
    return new SqlSessionTemplate(factory.getObject());
  }

  private static UserAccountTestFixture service(SqlSessionTemplate session) {
    return UserAccountTestFixture.create(
        session.getMapper(UserMapper.class), session.getMapper(UserAddressMapper.class));
  }

  private long next(String table) {
    assertTrue(List.of("mall_user", "user_address").contains(table));
    return fixture.queryForObject(
        "SELECT auto_increment FROM information_schema.tables WHERE table_schema=DATABASE() AND"
            + " table_name=?",
        Long.class,
        table);
  }

  private List<Map<String, Object>> indexDefinitions() {
    return fixture.queryForList(
        "SELECT table_name,index_name,non_unique,seq_in_index,column_name,index_type FROM"
            + " information_schema.statistics WHERE table_schema=DATABASE() AND table_name IN"
            + " ('mall_user','user_address') ORDER BY table_name,index_name,seq_in_index");
  }

  /** 有界并发fixture，只有owned数据源；每类最多16并发，超时取消本测试任务。 */
  private static List<Long> parallel(int count, IndexedInsert insert) throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(count);
    var executor = Executors.newFixedThreadPool(count);
    try {
      List<Callable<Long>> tasks = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        int position = index;
        tasks.add(
            () -> {
              barrier.await(15, TimeUnit.SECONDS);
              return insert.insert(position);
            });
      }
      List<Long> result = new ArrayList<>();
      for (var future : executor.invokeAll(tasks, 45, TimeUnit.SECONDS)) result.add(future.get());
      return result;
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
    }
  }

  private static void assertGenerated(List<Long> identifiers, long oldMaximum) {
    assertEquals(identifiers.size(), new HashSet<>(identifiers).size());
    assertTrue(
        identifiers.stream()
            .allMatch(
                identity -> identity != null && identity > oldMaximum && identity < MAX_SAFE_ID));
  }

  private static long identifier(ApiResponse<?> response, String key) {
    return new ObjectMapper().valueToTree(response.data).get(key).longValue();
  }

  private static CredentialsDTO credentials(String username) {
    CredentialsDTO credentials = new CredentialsDTO();
    credentials.username = username;
    credentials.password = "Fixture123!";
    return credentials;
  }

  private static AddressRequestDTO addressRequest() {
    AddressRequestDTO request = new AddressRequestDTO();
    request.receiver = "ID fixture";
    request.phone = "13800000000";
    request.detailAddress = "owned test";
    request.isDefault = false;
    return request;
  }

  private static UserPO user(String username) {
    UserPO user = new UserPO();
    user.username = username;
    user.passwordHash = "owned-fixture-hash";
    user.role = "USER";
    user.balance = new BigDecimal("10000.00");
    user.frozenBalance = BigDecimal.ZERO;
    user.status = 1;
    user.createdAt = LocalDateTime.now();
    user.updatedAt = user.createdAt;
    return user;
  }

  private static UserAddressPO address(Long userId) {
    UserAddressPO address = new UserAddressPO();
    address.userId = userId;
    address.receiverName = "owned";
    address.receiverPhone = "13800000000";
    address.regionDetail = "owned fixture";
    address.defaultAddress = false;
    return address;
  }

  private static List<Map<String, Object>> sourceFacts() {
    List<Map<String, Object>> rows =
        new ArrayList<>(
            source.queryForList(
                "CHECKSUM TABLE cloudmall_user.mall_user,cloudmall_user.user_address EXTENDED"));
    rows.addAll(
        source.queryForList(
            "SELECT COUNT(*) AS row_count,MAX(id) AS maximum_id FROM cloudmall_user.mall_user"));
    rows.addAll(
        source.queryForList(
            "SELECT COUNT(*) AS row_count,MAX(id) AS maximum_id FROM cloudmall_user.user_address"));
    return rows;
  }

  private static String configured(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true&connectTimeout=3000&socketTimeout=15000";
  }

  /** 有界并发fixture的单次owned插入，不作为生产接口或时钟替身。 */
  @FunctionalInterface
  private interface IndexedInsert {
    /**
     * @return 实际MyBatis回填的数据库主键
     */
    Long insert(int index) throws Exception;
  }
}
