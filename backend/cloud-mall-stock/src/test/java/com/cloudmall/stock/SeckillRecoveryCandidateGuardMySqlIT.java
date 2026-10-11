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

  /** 用于受控竞争writer的真实MyBatis代理，原查询和CAS均不模拟结果。 */
  private static SeckillQuotaSettlementMapper realMapper;

  /** 调用真实MyBatis的可观测Mapper，不模拟SQL结果。 */
  private static SeckillQuotaSettlementMapper observedMapper;

  /** 实际Redis连接，仅测试进程拥有连接生命周期。 */
  private static LettuceConnectionFactory connections;

  /** 仅owned键的准备/核验模板。 */
  private static StringRedisTemplate fixtureRedis;

  /** 执行生产Lua的可观测真实模板。 */
  private static StringRedisTemplate observedRedis;

  /** 实际候选查询返回行数；请求limit与物理返回分开计数。 */
  private static final List<Integer> queryResultSizes = new ArrayList<>();

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
    realMapper =
        new SqlSessionTemplate(factory.getObject()).getMapper(SeckillQuotaSettlementMapper.class);
    realMapper.ensureTable();
    var sqlAnswer = org.mockito.AdditionalAnswers.delegatesTo(realMapper);
    observedMapper =
        mock(
            SeckillQuotaSettlementMapper.class,
            invocation -> {
              Object result = sqlAnswer.answer(invocation);
              if ("findDue".equals(invocation.getMethod().getName())
                  || "findDueAfter".equals(invocation.getMethod().getName()))
                queryResultSizes.add(((List<?>) result).size());
              return result;
            });
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
            queryResultSizes.clear();
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
      queryResultSizes.clear();
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
      verify(observedMapper).findDueAfter(any(), eq(1L), eq(50));
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
    queryResultSizes.clear();
    clearInvocations(observedMapper, observedRedis);
    assertEquals(0, recovery.recoverDue());
    verify(observedMapper).findDue(any(), eq(50));
    verifyNoMoreInteractions(observedMapper);
    verifyNoInteractions(observedRedis);
    assertEquals(before, facts());
    assertEquals(redisBefore, redisFacts(keys));
  }

  @Test
  void inconsistentReleasedReadbackCannotProveCurrentLuaReceipt() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String variant :
        List.of(
            "id",
            "order-case",
            "order-accent",
            "order-space",
            "activity",
            "sku",
            "user",
            "idem-case",
            "idem-space",
            "quantity",
            "revision",
            "operation",
            "created",
            "receipt-null",
            "receipt-other"))
      assertions.add(
          () -> {
            reset();
            var original = row("RELEASE_PENDING", "owned-idem");
            seed(original);
            var keys = prepareRedis(original);
            var snapshot = installReleasedWriter(original, variant);
            queryResultSizes.clear();
            clearInvocations(observedMapper, observedRedis);
            int handled = recovery.recoverDue();
            assertAll(
                () -> assertEquals(0, handled, variant),
                () ->
                    verify(observedMapper)
                        .retry(
                            eq(original.orderNo),
                            eq(original.operationKey),
                            eq(2L),
                            any(),
                            eq("SECKILL_RELEASE_CAS_UNKNOWN"),
                            any(),
                            any()),
                () -> assertEquals(snapshot.get(), facts(), "被测路径不得修补当前坏终态: " + variant),
                () -> assertSingleReleaseProjection(original, keys));
            verify(observedMapper).findDue(any(), eq(50));
            verify(observedMapper)
                .claim(
                    eq(original.orderNo), eq(original.operationKey), eq(2L), any(), any(), any());
            verify(observedMapper)
                .markReleased(
                    eq(original.orderNo), eq(original.operationKey), eq(2L), eq(4L), any());
            verify(observedMapper).findSettlement(original.orderNo);
            verifyNoMoreInteractions(observedMapper);
          });
    assertAll(assertions);
  }

  @Test
  void exactCompetingWriterAndLuaReceiptAreStillCountedComplete() {
    for (String state : List.of("RELEASE_PENDING", "PROJECTION_BLOCKED")) {
      reset();
      var original = row(state, "😺".repeat(128));
      seed(original);
      var keys = prepareRedis(original);
      var snapshot = installReleasedWriter(original, "exact");
      queryResultSizes.clear();
      clearInvocations(observedMapper, observedRedis);
      assertEquals(1, recovery.recoverDue());
      verify(observedMapper).findSettlement(original.orderNo);
      verify(observedMapper, never()).retry(any(), any(), anyLong(), any(), any(), any(), any());
      assertEquals(snapshot.get(), facts());
      assertReleased(original);
      assertSingleReleaseProjection(original, keys);
    }
  }

  @Test
  void realBadHeadPagesCannotStarveHealthyTailAcrossRecoveryRounds() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (int badCount : List.of(50, 150))
      assertions.add(
          () -> {
            reset();
            var original = row("RELEASE_PENDING", "owned-idem");
            seed(original);
            fixture.update("UPDATE seckill_quota_settlement SET id=?", badCount + 1);
            seedBadHead(badCount);
            var keys = prepareRedis(original);
            var badBefore =
                fixture.queryForList(
                    "SELECT * FROM seckill_quota_settlement WHERE id<=? ORDER BY id", badCount);
            for (int round = 0; round <= badCount / 50; round++) {
              queryResultSizes.clear();
              clearInvocations(observedMapper, observedRedis);
              assertEquals(round == badCount / 50 ? 1 : 0, recovery.recoverDue());
              assertBoundedQueries();
            }
            assertReleased(original);
            assertEquals(
                badBefore,
                fixture.queryForList(
                    "SELECT * FROM seckill_quota_settlement WHERE id<=? ORDER BY id", badCount));
            assertSingleReleaseProjection(original, keys);
          });
    assertAll(assertions);
  }

  @Test
  void realRingWrapRecoversRequeuedHeadWithoutDuplicateQuota() {
    reset();
    var original = row("RELEASE_PENDING", "owned-idem");
    seed(original);
    fixture.update("UPDATE seckill_quota_settlement SET id=51");
    seedBadHead(50);
    var keys = prepareRedis(original);
    assertEquals(0, recovery.recoverDue());
    queryResultSizes.clear();
    clearInvocations(observedMapper, observedRedis);
    assertEquals(1, recovery.recoverDue());
    assertBoundedQueries();
    assertReleased(original);
    // 重新放回本次已成功事实，原Lua回执重放不回补第二次，首段坏记录不被修。
    fixture.update(
        "UPDATE seckill_quota_settlement SET"
            + " id=-1,status='RELEASE_PENDING',redis_revision=NULL,next_retry_at='2000-01-01' WHERE"
            + " order_no=?",
        original.orderNo);
    queryResultSizes.clear();
    clearInvocations(observedMapper, observedRedis);
    assertEquals(1, recovery.recoverDue());
    assertBoundedQueries();
    assertReleased(original);
    assertSingleReleaseProjection(original, keys);
    assertEquals(
        50,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM seckill_quota_settlement WHERE id>=1 AND id<=50 AND revision=1"
                + " AND lease_owner IS NULL",
            Integer.class));
  }

  /** 实际当前BIGINT id可负可正；原坏revision台账只准备，不延期/repair。 */
  private static void seedBadHead(int count) {
    String prefix = "bad-" + UUID.randomUUID().toString().replace("-", "");
    for (int recordId = 1; recordId <= count; recordId++) {
      String orderNo = prefix + "-" + recordId;
      fixture.update(
          "INSERT INTO"
              + " seckill_quota_settlement(id,order_no,activity_id,sku_id,user_id,idempotency_key,quantity,status,revision,operation_key,next_retry_at,created_at,updated_at)"
              + " VALUES(?,?,10,20,30,'bad-owned',1,'RELEASE_PENDING',1,?,'2000-01-01','2000-01-01','2000-01-01')",
          recordId,
          orderNo,
          orderNo + ":ROLLBACK");
    }
  }

  /** 所有候选查询实际返回行加总<=50且同now；单query limit<=50，不作无界遍历。 */
  private static void assertBoundedQueries() {
    var queries =
        org.mockito.Mockito.mockingDetails(observedMapper).getInvocations().stream()
            .filter(
                invocation ->
                    invocation.getMethod().getName().equals("findDue")
                        || invocation.getMethod().getName().equals("findDueAfter"))
            .toList();
    assertFalse(queries.isEmpty());
    assertTrue(queries.size() <= 2);
    assertEquals(queries.size(), queryResultSizes.size());
    assertTrue(queryResultSizes.stream().mapToInt(Integer::intValue).sum() <= 50);
    for (var query : queries) {
      int requested = (Integer) query.getArguments()[query.getArguments().length - 1];
      assertTrue(requested > 0 && requested <= 50);
    }
    Object occurredAt = queries.get(0).getArgument(0);
    for (var query : queries) assertEquals(occurredAt, query.getArgument(0));
  }

  /** 在被测CAS之前提交真实writer，再真实执行重复CAS0；仅owned注入当前行故障。 */
  private static java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>
      installReleasedWriter(SeckillQuotaSettlementPO original, String variant) {
    var snapshot =
        new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
    doAnswer(
            invocation -> {
              String orderNo = invocation.getArgument(0);
              String operationKey = invocation.getArgument(1);
              long revision = invocation.getArgument(2);
              long redisRevision = invocation.getArgument(3);
              String owner = invocation.getArgument(4);
              assertEquals(
                  1,
                  realMapper.markReleased(orderNo, operationKey, revision, redisRevision, owner));
              switch (variant) {
                case "id" -> fixture.update("UPDATE seckill_quota_settlement SET id=2");
                case "order-case" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET order_no=?",
                        original.orderNo.toUpperCase(java.util.Locale.ROOT));
                case "order-accent" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET order_no=?",
                        original.orderNo.replace("cm37", "cḿ37"));
                case "order-space" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET order_no=?", original.orderNo + " ");
                case "activity" ->
                    fixture.update("UPDATE seckill_quota_settlement SET activity_id=activity_id+1");
                case "sku" -> fixture.update("UPDATE seckill_quota_settlement SET sku_id=sku_id+1");
                case "user" ->
                    fixture.update("UPDATE seckill_quota_settlement SET user_id=user_id+1");
                case "idem-case" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET idempotency_key='OWNED-IDEM'");
                case "idem-space" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET idempotency_key='owned-idem '");
                case "quantity" -> fixture.update("UPDATE seckill_quota_settlement SET quantity=2");
                case "revision" -> fixture.update("UPDATE seckill_quota_settlement SET revision=3");
                case "operation" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET operation_key=?",
                        original.orderNo + ":CONFIRM");
                case "created" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET created_at='2000-01-01 00:00:00.001'");
                case "receipt-null" ->
                    fixture.update("UPDATE seckill_quota_settlement SET redis_revision=NULL");
                case "receipt-other" ->
                    fixture.update("UPDATE seckill_quota_settlement SET redis_revision=5");
                case "exact" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET"
                            + " updated_at='2026-10-12',last_error='diagnostic-only'");
                default -> throw new AssertionError(variant);
              }
              snapshot.set(facts());
              int changed =
                  realMapper.markReleased(orderNo, operationKey, revision, redisRevision, owner);
              assertEquals(0, changed, "必须触发真实已提交终态的CAS0");
              return changed;
            })
        .when(observedMapper)
        .markReleased(any(), any(), anyLong(), anyLong(), any());
    return snapshot;
  }

  /** 保原Lua只调用一次且只回补一次；不修当前坏读回，不减用户计数。 */
  private static void assertSingleReleaseProjection(
      SeckillQuotaSettlementPO original, List<String> keys) {
    verify(observedRedis, times(1))
        .execute(
            any(org.springframework.data.redis.core.script.RedisScript.class),
            anyList(),
            eq(original.orderNo),
            eq(original.operationKey),
            eq("2"),
            eq("1"));
    assertEquals("6", fixtureRedis.opsForValue().get(keys.get(0)));
    assertEquals("4", fixtureRedis.opsForValue().get(keys.get(4)));
    assertEquals(
        "APPLIED|" + original.operationKey + "|2|4", fixtureRedis.opsForValue().get(keys.get(3)));
    assertEquals(original.orderNo, fixtureRedis.opsForValue().get(keys.get(2)));
    assertEquals("2", fixtureRedis.opsForValue().get(keys.get(5)));
  }

  /** 测试间仅清自有库，不重启中间件/改其它键。 */
  private static void reset() {
    org.mockito.Mockito.reset(observedMapper);
    queryResultSizes.clear();
    recovery = new SeckillQuotaSettlementRecovery(observedMapper, observedRedis);
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
    var stored =
        fixture.queryForMap(
            "SELECT * FROM seckill_quota_settlement WHERE order_no=?", original.orderNo);
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
