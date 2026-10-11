package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementRecovery;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 实际MyBatis与原Redis Lua的恢复候选验证；只拥有随机库及精确检查不存在的Redis键。 */
class SeckillRecoveryCandidateGuardMySqlIT {
  /** 原Stock库仅检查表清单/checksum。 */
  private static JdbcTemplate source;

  /** 源Stock原表清单及校验和。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 当前IT唯一数据库。 */
  private static String database;

  /** 仅owned事实准备/核验数据源。 */
  private static JdbcTemplate fixture;

  /** 调用真实MyBatis的可观测Mapper，不模拟SQL结果。 */
  private static SeckillQuotaSettlementMapper observedMapper;

  /** 实际Redis连接，仅测试进程拥有连接生命周期。 */
  private static LettuceConnectionFactory connections;

  /** 仅owned键的准备/核验模板。 */
  private static StringRedisTemplate fixtureRedis;

  /** 执行生产Lua的可观测真实模板。 */
  private static StringRedisTemplate observedRedis;

  /** 当前恢复器，未注册定时任务或业务App。 */
  private static SeckillQuotaSettlementRecovery recovery;

  /** 经过逐键不存在检查且由本fixture创建的全部键。 */
  private static final Set<String> ownedKeys = new LinkedHashSet<>();

  /** 原事实与不应发生任何普通库存写入的表。 */
  private static final List<String> TABLES =
      List.of(
          "stock_flow",
          "stock_reservation_request",
          "seckill_reservation",
          "seckill_quota_settlement",
          "stock_sku",
          "stock_cache_repair");

  @BeforeAll
  static void setup() throws Exception {
    String url = System.getProperty("cloudmall.mysql.test.url");
    String redisPort = System.getProperty("cloudmall.redis.test.port");
    if (url == null
        || !url.matches("jdbc:mysql://[^/]+/cloudmall_stock(\\?.*)?")
        || redisPort == null) throw new IllegalStateException("显式IT需要Stock URL及Redis测试端口");
    source = new JdbcTemplate(dataSource(url));
    sourceFingerprint = fingerprint();
    database = "cm16t37_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    DataSource owned = dataSource(url.replace("/cloudmall_stock", "/" + database));
    fixture = new JdbcTemplate(owned);
    for (String table : TABLES)
      if (!"seckill_quota_settlement".equals(table))
        fixture.execute("CREATE TABLE " + table + " LIKE cloudmall_stock." + table);
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(SeckillQuotaSettlementMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(owned);
    factory.setConfiguration(configuration);
    var mapper =
        new SqlSessionTemplate(factory.getObject()).getMapper(SeckillQuotaSettlementMapper.class);
    mapper.ensureTable();
    observedMapper =
        mock(SeckillQuotaSettlementMapper.class, org.mockito.AdditionalAnswers.delegatesTo(mapper));
    var redisConfiguration =
        new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(redisPort));
    redisConfiguration.setPassword("root");
    connections = new LettuceConnectionFactory(redisConfiguration);
    connections.afterPropertiesSet();
    fixtureRedis = new StringRedisTemplate(connections);
    observedRedis = spy(new StringRedisTemplate(connections));
    recovery = new SeckillQuotaSettlementRecovery(observedMapper, observedRedis);
  }

  @AfterEach
  void clearOwnedRedisKeys() {
    if (fixtureRedis != null && !ownedKeys.isEmpty()) {
      fixtureRedis.delete(ownedKeys);
      assertEquals(0L, fixtureRedis.countExistingKeys(ownedKeys));
    }
  }

  @AfterAll
  static void cleanup() {
    try {
      if (fixtureRedis != null && !ownedKeys.isEmpty()) {
        fixtureRedis.delete(ownedKeys);
        assertEquals(0L, fixtureRedis.countExistingKeys(ownedKeys));
      }
      if (source != null) assertEquals(sourceFingerprint, fingerprint());
    } finally {
      try {
        if (database != null && database.matches("cm16t37_[0-9a-f]{32}")) {
          source.execute("DROP DATABASE " + database);
          assertEquals(
              0,
              source.queryForObject(
                  "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                  Integer.class,
                  database));
        }
      } finally {
        if (connections != null) connections.destroy();
      }
    }
    System.out.println(
        "CM16T37 source Stock checksums unchanged; owned database removed; owned Redis keys absent:"
            + " "
            + ownedKeys.size());
  }

  @Test
  void invalidPersistedCandidatesCannotClaimWriteOrExecuteRealLua() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String variant :
        List.of(
            "foreign-operation",
            "case-operation",
            "accent-operation",
            "space-operation",
            "case-state",
            "activity-zero",
            "activity-negative",
            "sku-zero",
            "sku-negative",
            "user-zero",
            "user-negative",
            "empty-idem",
            "space-idem",
            "unicode-blank-idem",
            "literal-null-idem"))
      assertions.add(
          () -> {
            reset();
            var original = row("RELEASE_PENDING", "owned-idem");
            switch (variant) {
              case "foreign-operation" -> original.operationKey = "foreign:ROLLBACK";
              case "case-operation" ->
                  original.operationKey =
                      original.orderNo.toUpperCase(java.util.Locale.ROOT) + ":ROLLBACK";
              case "accent-operation" ->
                  original.operationKey = original.orderNo.replace("cm37", "cḿ37") + ":ROLLBACK";
              case "space-operation" -> original.operationKey = original.orderNo + " :ROLLBACK";
              case "case-state" -> original.status = "release_pending";
              case "activity-zero" -> original.activityId = 0L;
              case "activity-negative" -> original.activityId = -1L;
              case "sku-zero" -> original.skuId = 0L;
              case "sku-negative" -> original.skuId = -1L;
              case "user-zero" -> original.userId = 0L;
              case "user-negative" -> original.userId = -1L;
              case "empty-idem" -> original.idempotencyKey = "";
              case "space-idem" -> original.idempotencyKey = " ";
              case "unicode-blank-idem" -> original.idempotencyKey = "\u2003";
              case "literal-null-idem" -> original.idempotencyKey = "null";
              default -> throw new AssertionError(variant);
            }
            seed(original);
            var keys = prepareRedis(original);
            var before = facts();
            var redisBefore = redisFacts(keys);
            clearInvocations(observedMapper, observedRedis);
            int handled = recovery.recoverDue();
            assertAll(
                () -> assertEquals(0, handled, variant),
                () -> {
                  verify(observedMapper).findDue(any(), eq(50));
                  verifyNoMoreInteractions(observedMapper);
                },
                () -> verifyNoInteractions(observedRedis),
                () -> assertEquals(before, facts(), variant),
                () -> assertEquals(redisBefore, redisFacts(keys), variant));
          });
    assertAll(assertions);
  }

  @Test
  void healthyPendingAndBlockedRollbackKeepOneRealQuotaReleaseAndReceiptReplay() {
    for (String state : List.of("RELEASE_PENDING", "PROJECTION_BLOCKED")) {
      reset();
      var original = row(state, "😺".repeat(128));
      seed(original);
      var keys = prepareRedis(original);
      var before = facts();
      clearInvocations(observedMapper, observedRedis);
      assertEquals(1, recovery.recoverDue());
      assertEquals("6", fixtureRedis.opsForValue().get(keys.get(0)));
      assertEquals("4", fixtureRedis.opsForValue().get(keys.get(4)));
      assertEquals(
          "APPLIED|" + original.operationKey + "|2|4", fixtureRedis.opsForValue().get(keys.get(3)));
      assertEquals(original.orderNo, fixtureRedis.opsForValue().get(keys.get(2)));
      assertEquals("2", fixtureRedis.opsForValue().get(keys.get(5)));
      assertReleased(original);
      var after = facts();
      for (int tableIndex = 0; tableIndex < TABLES.size(); tableIndex++)
        if (tableIndex != 3) assertEquals(before.get(tableIndex), after.get(tableIndex));
      // 演练“Redis成功后DB结果丢失”：原意图重放，Lua回执不能二次INCR。
      fixture.update(
          "UPDATE seckill_quota_settlement SET"
              + " status=?,redis_revision=NULL,lease_owner=NULL,lease_until=NULL,next_retry_at='2000-01-01'"
              + " WHERE order_no=?",
          state,
          original.orderNo);
      assertEquals(1, recovery.recoverDue());
      assertReleased(original);
      assertEquals("6", fixtureRedis.opsForValue().get(keys.get(0)));
      assertEquals("4", fixtureRedis.opsForValue().get(keys.get(4)));
      assertEquals("2", fixtureRedis.opsForValue().get(keys.get(5)));
      verify(observedMapper, times(2)).findDue(any(), eq(50));
      verify(observedMapper, times(2))
          .claim(eq(original.orderNo), eq(original.operationKey), eq(2L), any(), any(), any());
      verify(observedMapper, times(2))
          .markReleased(eq(original.orderNo), eq(original.operationKey), eq(2L), eq(4L), any());
      verifyNoMoreInteractions(observedMapper);
    }
  }

  @Test
  void blockedConfirmDirectionIsReadOnlyEvenWithReleaseProjectionPresent() {
    reset();
    var original = row("PROJECTION_BLOCKED", "owned-idem");
    original.operationKey = original.orderNo + ":CONFIRM";
    seed(original);
    var keys = prepareRedis(original);
    var before = facts();
    var redisBefore = redisFacts(keys);
    clearInvocations(observedMapper, observedRedis);
    assertEquals(0, recovery.recoverDue());
    verify(observedMapper).findDue(any(), eq(50));
    verifyNoMoreInteractions(observedMapper);
    verifyNoInteractions(observedRedis);
    assertEquals(before, facts());
    assertEquals(redisBefore, redisFacts(keys));
  }

  /** 测试间仅清自有库，不重启中间件/改其它键。 */
  private static void reset() {
    for (String table : TABLES) fixture.update("DELETE FROM " + table);
    if (!ownedKeys.isEmpty()) fixtureRedis.delete(ownedKeys);
  }

  /** 仅当前VARCHAR/NOTNULL准备可存原事实，不ALTER。 */
  private static void seed(SeckillQuotaSettlementPO original) {
    fixture.update(
        "INSERT INTO"
            + " seckill_quota_settlement(id,order_no,activity_id,sku_id,user_id,idempotency_key,quantity,status,revision,operation_key,next_retry_at,created_at,updated_at)"
            + " VALUES(1,?,?,?,?,?,1,?,2,?,'2000-01-01','2000-01-01','2000-01-01')",
        original.orderNo,
        original.activityId,
        original.skuId,
        original.userId,
        original.idempotencyKey,
        original.status,
        original.operationKey);
  }

  /** 每个原键确认不存在后才拥有；不使用SCAN/FLUSH，不清用户已存在键。 */
  private static List<String> prepareRedis(SeckillQuotaSettlementPO original) {
    String dimensions = original.activityId + ":" + original.skuId;
    List<String> keys =
        List.of(
            "seckill:stock:" + dimensions,
            "seckill:meta:" + dimensions,
            "seckill:accepted:"
                + dimensions
                + ":"
                + original.userId
                + ":"
                + original.idempotencyKey,
            "seckill:quota:op:" + original.orderNo,
            "seckill:quota:revision:" + dimensions,
            "seckill:user:" + original.activityId + ":" + original.userId);
    for (String key : keys)
      assertFalse(Boolean.TRUE.equals(fixtureRedis.hasKey(key)), "预期owned键已存在，停止准备");
    ownedKeys.addAll(keys);
    fixtureRedis.opsForValue().set(keys.get(0), "5");
    fixtureRedis
        .opsForHash()
        .putAll(
            keys.get(1),
            Map.of(
                "stockLimit",
                "10",
                "perUserLimit",
                "10",
                "startAt",
                "2000-01-01T00:00:00+08:00",
                "endAt",
                "2099-01-01T00:00:00+08:00"));
    fixtureRedis.opsForValue().set(keys.get(2), original.orderNo);
    fixtureRedis.opsForValue().set(keys.get(3), "RESERVED|" + original.orderNo + "|1");
    fixtureRedis.opsForValue().set(keys.get(4), "3");
    fixtureRedis.opsForValue().set(keys.get(5), "2");
    for (String key : keys) fixtureRedis.expire(key, Duration.ofMinutes(5));
    return keys;
  }

  /** 核自有投影内容；不比较自然流逝的TTL。 */
  private static List<Object> redisFacts(List<String> keys) {
    return List.of(
        fixtureRedis.opsForValue().get(keys.get(0)),
        fixtureRedis.opsForHash().entries(keys.get(1)),
        fixtureRedis.opsForValue().get(keys.get(2)),
        fixtureRedis.opsForValue().get(keys.get(3)),
        fixtureRedis.opsForValue().get(keys.get(4)),
        fixtureRedis.opsForValue().get(keys.get(5)));
  }

  /** 核合法方向的DB回执，所有原不可变维度不被重写。 */
  private static void assertReleased(SeckillQuotaSettlementPO original) {
    var stored = fixture.queryForMap("SELECT * FROM seckill_quota_settlement");
    assertEquals("RELEASED", stored.get("status"));
    assertEquals(original.orderNo, stored.get("order_no"));
    assertEquals(original.operationKey, stored.get("operation_key"));
    assertEquals(original.idempotencyKey, stored.get("idempotency_key"));
    assertEquals(original.activityId, stored.get("activity_id"));
    assertEquals(original.skuId, stored.get("sku_id"));
    assertEquals(original.userId, stored.get("user_id"));
    assertEquals(2L, ((Number) stored.get("revision")).longValue());
    assertEquals(4L, ((Number) stored.get("redis_revision")).longValue());
    assertNull(stored.get("lease_owner"));
    assertNull(stored.get("lease_until"));
  }

  /** 完整六表事实快照。 */
  private static List<List<Map<String, Object>>> facts() {
    return TABLES.stream().map(table -> fixture.queryForList("SELECT * FROM " + table)).toList();
  }

  /** 源Stock只读表清单/checksum。 */
  private static List<Map<String, Object>> fingerprint() {
    var values = new ArrayList<>(source.queryForList("SHOW TABLES FROM cloudmall_stock"));
    for (String table : source.queryForList("SHOW TABLES FROM cloudmall_stock", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法源表");
      values.addAll(source.queryForList("CHECKSUM TABLE cloudmall_stock." + table));
    }
    return values;
  }

  /** 固定项目凭证及Shanghai DATETIME语义，不修改全局时区。 */
  private static DataSource dataSource(String url) {
    return new DriverManagerDataSource(
        url
            + (url.contains("?") ? "&" : "?")
            + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true",
        "root",
        "root");
  }

  /** 使用随机原订单和随机维度，防止任何生产格式键和用户数据重合。 */
  private static SeckillQuotaSettlementPO row(String state, String idempotency) {
    var original = new SeckillQuotaSettlementPO();
    original.orderNo = "cm37-" + UUID.randomUUID().toString().replace("-", "");
    original.operationKey = original.orderNo + ":ROLLBACK";
    original.status = state;
    original.revision = 2L;
    original.quantity = 1;
    original.activityId = ThreadLocalRandom.current().nextLong(1000000000000L, 2000000000000L);
    original.skuId = ThreadLocalRandom.current().nextLong(1000000000000L, 2000000000000L);
    original.userId = ThreadLocalRandom.current().nextLong(1000000000000L, 2000000000000L);
    original.idempotencyKey = idempotency;
    return original;
  }
}
