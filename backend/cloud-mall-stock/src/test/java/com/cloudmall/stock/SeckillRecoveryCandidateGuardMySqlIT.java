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
    for (String acceptedState : List.of("ACCEPTED", "PUBLISHED", "ORDER_CREATED"))
      for (String state : List.of("RELEASE_PENDING", "PROJECTION_BLOCKED")) {
        reset();
        var original = row(state, "😺".repeat(128));
        seed(original);
        fixture.update("UPDATE seckill_reservation SET status=?", acceptedState);
        var keys = prepareRedis(original);
        var before = facts();
        queryResultSizes.clear();
        clearInvocations(observedMapper, observedRedis);
        assertEquals(1, recovery.recoverDue());
        assertEquals("6", fixtureRedis.opsForValue().get(keys.get(0)));
        assertEquals("4", fixtureRedis.opsForValue().get(keys.get(4)));
        assertEquals(
            "APPLIED|" + original.operationKey + "|2|4",
            fixtureRedis.opsForValue().get(keys.get(3)));
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
        verify(observedMapper, times(2)).findSettlement(original.orderNo);
        verify(observedMapper).findDueAfter(any(), eq(1L), eq(50));
        verify(observedMapper, times(2))
            .claim(eq(original.orderNo), eq(original.operationKey), eq(2L), any(), any(), any());
        verify(observedMapper, times(2))
            .markReleased(
                eq(original.orderNo),
                eq(original.operationKey),
                eq(2L),
                eq(4L),
                any(),
                any(SeckillQuotaSettlementPO.class));
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
                            any(),
                            any(SeckillQuotaSettlementPO.class)),
                () -> assertEquals(snapshot.get(), facts(), "被测路径不得修补当前坏终态: " + variant),
                () -> assertSingleReleaseProjection(original, keys));
            verify(observedMapper).findDue(any(), eq(50));
            verify(observedMapper)
                .claim(
                    eq(original.orderNo), eq(original.operationKey), eq(2L), any(), any(), any());
            verify(observedMapper)
                .markReleased(
                    eq(original.orderNo),
                    eq(original.operationKey),
                    eq(2L),
                    eq(4L),
                    any(),
                    any(SeckillQuotaSettlementPO.class));
            verify(observedMapper, times(2)).findSettlement(original.orderNo);
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
      verify(observedMapper, times(2)).findSettlement(original.orderNo);
      verify(observedMapper, never())
          .retry(
              any(),
              any(),
              anyLong(),
              any(),
              any(),
              any(),
              any(),
              any(SeckillQuotaSettlementPO.class));
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
              SeckillQuotaSettlementPO expected = invocation.getArgument(5);
              assertEquals(
                  1,
                  realMapper.markReleased(
                      orderNo, operationKey, revision, redisRevision, owner, expected));
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
                  realMapper.markReleased(
                      orderNo, operationKey, revision, redisRevision, owner, expected);
              assertEquals(0, changed, "必须触发真实已提交终态的CAS0");
              return changed;
            })
        .when(observedMapper)
        .markReleased(
            any(), any(), anyLong(), anyLong(), any(), any(SeckillQuotaSettlementPO.class));
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

  @Test
  void realClaimCannotLeaseMissingOrMismatchedAcceptanceSource() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String state : List.of("RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String variant : badAcceptanceSources())
        assertions.add(
            () -> {
              reset();
              var original = row(state, "owned-idem");
              seed(original);
              corruptAcceptanceSource(original, variant);
              var before = facts();
              var sampledNow = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
              int leased =
                  realMapper.claim(
                      original.orderNo,
                      original.operationKey,
                      original.revision,
                      "owned-source-claim",
                      sampledNow.plusSeconds(30),
                      sampledNow);
              assertAll(
                  () -> assertEquals(0, leased, state + ":" + variant),
                  () -> assertEquals(before, facts(), state + ":" + variant));
            });
    assertAll(assertions);
  }

  @Test
  void recoveryWithHealthyRedisCannotReleaseUnprovenAcceptanceSource() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String variant : badAcceptanceSources())
      assertions.add(
          () -> {
            reset();
            var original = row("RELEASE_PENDING", "owned-idem");
            seed(original);
            corruptAcceptanceSource(original, variant);
            var keys = prepareRedis(original);
            var before = facts();
            var redisBefore = redisFacts(keys);
            clearInvocations(observedMapper, observedRedis);
            int handled = recovery.recoverDue();
            assertAll(
                () -> assertEquals(0, handled, variant),
                () -> {
                  verify(observedMapper).findDue(any(), eq(50));
                  verify(observedMapper)
                      .claim(
                          eq(original.orderNo),
                          eq(original.operationKey),
                          eq(2L),
                          any(),
                          any(),
                          any());
                  verifyNoMoreInteractions(observedMapper);
                },
                () -> verifyNoInteractions(observedRedis),
                () -> assertEquals(before, facts(), variant),
                () -> assertEquals(redisBefore, redisFacts(keys), variant));
          });
    assertAll(assertions);
  }

  /** 原 NOTNULL/VARCHAR/BIGINT 能实际保存的缺来源矩阵，不 ALTER。 */
  private static List<String> badAcceptanceSources() {
    return List.of(
        "missing",
        "pending",
        "unknown",
        "rejected",
        "status-case",
        "status-space",
        "order-foreign",
        "order-case",
        "order-accent",
        "order-space",
        "activity",
        "sku",
        "user",
        "idem-foreign",
        "idem-case",
        "idem-accent",
        "idem-space");
  }

  /** 只改变自有受理事实；结算与Redis均保原合法tuple以复现错来源领取。 */
  private static void corruptAcceptanceSource(SeckillQuotaSettlementPO original, String variant) {
    switch (variant) {
      case "missing" -> fixture.update("DELETE FROM seckill_reservation");
      case "pending", "unknown", "rejected" ->
          fixture.update(
              "UPDATE seckill_reservation SET status=?",
              variant.toUpperCase(java.util.Locale.ROOT));
      case "status-case" -> fixture.update("UPDATE seckill_reservation SET status='accepted'");
      case "status-space" -> fixture.update("UPDATE seckill_reservation SET status='ACCEPTED '");
      case "order-foreign" ->
          fixture.update("UPDATE seckill_reservation SET order_no='foreign-owned-source'");
      case "order-case" ->
          fixture.update(
              "UPDATE seckill_reservation SET order_no=?",
              original.orderNo.toUpperCase(java.util.Locale.ROOT));
      case "order-accent" ->
          fixture.update(
              "UPDATE seckill_reservation SET order_no=?",
              original.orderNo.replace("cm37", "cḿ37"));
      case "order-space" ->
          fixture.update("UPDATE seckill_reservation SET order_no=?", original.orderNo + " ");
      case "activity" ->
          fixture.update("UPDATE seckill_reservation SET activity_id=?", original.activityId + 1);
      case "sku" -> fixture.update("UPDATE seckill_reservation SET sku_id=?", original.skuId + 1);
      case "user" ->
          fixture.update("UPDATE seckill_reservation SET user_id=?", original.userId + 1);
      case "idem-foreign" ->
          fixture.update("UPDATE seckill_reservation SET idempotency_key='foreign-owned-idem'");
      case "idem-case" ->
          fixture.update("UPDATE seckill_reservation SET idempotency_key='OWNED-IDEM'");
      case "idem-accent" ->
          fixture.update("UPDATE seckill_reservation SET idempotency_key='owned-idém'");
      case "idem-space" ->
          fixture.update("UPDATE seckill_reservation SET idempotency_key='owned-idem '");
      default -> throw new AssertionError(variant);
    }
  }

  @Test
  void actualAppliedLuaCannotBypassMissingOrWrongOriginalAcceptanceMarker() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String variant : appliedAcceptanceFaults())
      assertions.add(
          () -> {
            reset();
            var original = row("RELEASE_PENDING", "owned-idem");
            seed(original);
            var keys = prepareRedis(original);
            assertEquals(1, recovery.recoverDue());
            assertReleased(original);
            assertSingleReleaseProjection(original, keys);
            corruptAcceptedMarker(original, keys.get(2), variant);
            var before = redisDumpFacts(keys);
            var databaseBefore = facts();
            assertEquals(Long.valueOf(0), executeReleaseLua(original, keys), variant);
            assertEquals(before, redisDumpFacts(keys), variant);
            assertEquals(databaseBefore, facts());
          });
    assertAll(assertions);
  }

  @Test
  void appliedRecoveryBlocksWrongWitnessAndKnownOriginalWitnessRestoresOnlyReceipt() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String variant : appliedAcceptanceFaults())
      assertions.add(
          () -> {
            reset();
            var original = row("RELEASE_PENDING", "owned-idem");
            seed(original);
            var keys = prepareRedis(original);
            assertEquals(1, recovery.recoverDue());
            assertSingleReleaseProjection(original, keys);
            // 受控模拟首次Lua成功后DB完成结果丢失，不再创造新order/operation/revision。
            fixture.update(
                "UPDATE seckill_quota_settlement SET"
                    + " status='RELEASE_PENDING',redis_revision=NULL,lease_owner=NULL,lease_until=NULL,next_retry_at='2000-01-01'");
            corruptAcceptedMarker(original, keys.get(2), variant);
            var before = facts();
            var redisBefore = redisDumpFacts(keys);
            clearInvocations(observedMapper, observedRedis);
            int handled = recovery.recoverDue();
            assertAll(
                () -> assertEquals(0, handled, variant),
                () ->
                    assertEquals(
                        "PROJECTION_BLOCKED",
                        fixture.queryForObject(
                            "SELECT status FROM seckill_quota_settlement", String.class)),
                () ->
                    assertNull(
                        fixture
                            .queryForMap("SELECT * FROM seckill_quota_settlement")
                            .get("redis_revision")),
                () ->
                    verify(observedMapper, never())
                        .markReleased(any(), any(), anyLong(), anyLong(), any(), any()),
                () -> assertEquals(redisBefore, redisDumpFacts(keys), variant));
            verify(observedRedis)
                .execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class),
                    anyList(),
                    eq(original.orderNo),
                    eq(original.operationKey),
                    eq("2"),
                    eq("1"));
            var after = facts();
            for (int tableIndex = 0; tableIndex < TABLES.size(); tableIndex++)
              if (tableIndex != 3) assertEquals(before.get(tableIndex), after.get(tableIndex));
            var originalStable = new java.util.HashMap<>(before.get(3).get(0));
            var actualStable = new java.util.HashMap<>(after.get(3).get(0));
            for (String changed :
                List.of(
                    "status",
                    "last_error",
                    "lease_owner",
                    "lease_until",
                    "next_retry_at",
                    "updated_at")) {
              originalStable.remove(changed);
              actualStable.remove(changed);
            }
            assertEquals(originalStable, actualStable);
            // 仅恢复本fixture已知原值；不是生产补证或对用户marker自动修复。
            fixtureRedis.delete(keys.get(2));
            fixtureRedis.opsForValue().set(keys.get(2), original.orderNo, Duration.ofMinutes(5));
            fixture.update("UPDATE seckill_quota_settlement SET next_retry_at='2000-01-01'");
            clearInvocations(observedRedis);
            var restoredRedis = redisDumpFacts(keys);
            assertEquals(1, recovery.recoverDue());
            assertReleased(original);
            assertSingleReleaseProjection(original, keys);
            assertEquals(restoredRedis, redisDumpFacts(keys));
          });
    assertAll(assertions);
  }

  @Test
  void legitimateAppliedReceiptRemainsReadOnlyWhenOtherProjectionKeysAreAbsent() {
    reset();
    var original = row("RELEASE_PENDING", "owned-idem");
    seed(original);
    var keys = prepareRedis(original);
    assertEquals(1, recovery.recoverDue());
    assertSingleReleaseProjection(original, keys);
    fixtureRedis.delete(List.of(keys.get(0), keys.get(1), keys.get(4)));
    var before = redisDumpFacts(keys);
    var databaseBefore = facts();
    assertEquals(Long.valueOf(4), executeReleaseLua(original, keys));
    assertEquals(before, redisDumpFacts(keys));
    assertEquals(databaseBefore, facts());
    assertEquals(original.orderNo, fixtureRedis.opsForValue().get(keys.get(2)));
    assertEquals(
        "APPLIED|" + original.operationKey + "|2|4", fixtureRedis.opsForValue().get(keys.get(3)));
    assertEquals("2", fixtureRedis.opsForValue().get(keys.get(5)));
  }

  /** 八种真实可存的受理witness故障，不模拟脚本返回值。 */
  private static List<String> appliedAcceptanceFaults() {
    return List.of("missing", "hash", "list", "set", "foreign", "case", "accent", "space");
  }

  /** 仅改已拥有的原accepted键；wrong type也保留以核不被Lua改写。 */
  private static void corruptAcceptedMarker(
      SeckillQuotaSettlementPO original, String acceptedKey, String variant) {
    fixtureRedis.delete(acceptedKey);
    switch (variant) {
      case "missing" -> {
        return;
      }
      case "hash" -> fixtureRedis.opsForHash().put(acceptedKey, "owned-field", "owned-value");
      case "list" -> fixtureRedis.opsForList().rightPush(acceptedKey, "owned-value");
      case "set" -> fixtureRedis.opsForSet().add(acceptedKey, "owned-value");
      case "foreign" -> fixtureRedis.opsForValue().set(acceptedKey, "other-owned-order");
      case "case" ->
          fixtureRedis
              .opsForValue()
              .set(acceptedKey, original.orderNo.toUpperCase(java.util.Locale.ROOT));
      case "accent" ->
          fixtureRedis.opsForValue().set(acceptedKey, original.orderNo.replace("cm37", "cḿ37"));
      case "space" -> fixtureRedis.opsForValue().set(acceptedKey, original.orderNo + " ");
      default -> throw new AssertionError(variant);
    }
    fixtureRedis.expire(acceptedKey, Duration.ofMinutes(5));
  }

  /** 原生产资源脚本在真实Redis直接执行；KEYS不含不应变更的user计数。 */
  private static Long executeReleaseLua(SeckillQuotaSettlementPO original, List<String> keys) {
    var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>();
    script.setLocation(
        new org.springframework.core.io.ClassPathResource("lua/seckill-quota-release.lua"));
    script.setResultType(Long.class);
    return fixtureRedis.execute(
        script,
        keys.subList(0, 5),
        original.orderNo,
        original.operationKey,
        String.valueOf(original.revision),
        String.valueOf(original.quantity));
  }

  /** Redis DUMP比较各原类型完整内容且允许缺键；不比较自然流逝的TTL。 */
  private static List<String> redisDumpFacts(List<String> keys) {
    var contents = new ArrayList<String>();
    for (String key : keys) {
      byte[] serialized = fixtureRedis.dump(key);
      contents.add(
          serialized == null
              ? "<missing>"
              : java.util.Base64.getEncoder().encodeToString(serialized));
    }
    return contents;
  }

  @Test
  void recoveryWritesCannotFinalizeOrDiagnoseMissingOrChangedAcceptanceSource() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String action : List.of("release", "block", "retry"))
      for (String variant : badAcceptanceSources())
        assertions.add(
            () -> {
              reset();
              var original = row("RELEASE_PENDING", "owned-idem");
              seed(original);
              String owner = "owned-source-writer-" + UUID.randomUUID();
              var occurredAt = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
              assertEquals(
                  1,
                  realMapper.claim(
                      original.orderNo,
                      original.operationKey,
                      2L,
                      owner,
                      occurredAt.plusMinutes(1),
                      occurredAt));
              corruptAcceptanceSource(original, variant);
              var before = facts();
              int affected = applyRecoveryWrite(action, original, owner, occurredAt);
              assertAll(
                  () -> assertEquals(0, affected, action + ":" + variant),
                  () -> assertEquals(before, facts(), action + ":" + variant));
            });
    assertAll(assertions);
  }

  @Test
  void sourceChangedAfterRealLuaCannotBeFinalizedBlockedOrRetriedByOldRecovery() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String outcome : List.of("release", "block", "error"))
      assertions.add(
          () -> {
            reset();
            var original = row("RELEASE_PENDING", "owned-idem");
            seed(original);
            var keys = prepareRedis(original);
            if ("block".equals(outcome)) fixtureRedis.delete(keys.get(1));
            var snapshot =
                new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
            var redisSnapshot = new java.util.concurrent.atomic.AtomicReference<List<String>>();
            doAnswer(
                    invocation -> {
                      Object receipt = invocation.callRealMethod();
                      fixture.update("UPDATE seckill_reservation SET sku_id=sku_id+1");
                      snapshot.set(facts());
                      redisSnapshot.set(redisDumpFacts(keys));
                      if ("error".equals(outcome))
                        throw new org.springframework.data.redis.RedisConnectionFailureException(
                            "owned-source-response-lost");
                      return receipt;
                    })
                .when(observedRedis)
                .execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class),
                    anyList(),
                    any(),
                    any(),
                    any(),
                    any());
            clearInvocations(observedMapper, observedRedis);
            assertAll(
                () -> assertEquals(0, recovery.recoverDue(), outcome),
                () -> assertEquals(snapshot.get(), facts(), outcome),
                () -> assertEquals(redisSnapshot.get(), redisDumpFacts(keys), outcome));
            verify(observedRedis)
                .execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class),
                    anyList(),
                    eq(original.orderNo),
                    eq(original.operationKey),
                    eq("2"),
                    eq("1"));
            assertEquals(
                "RELEASE_PENDING",
                fixture.queryForObject(
                    "SELECT status FROM seckill_quota_settlement", String.class));
            assertNull(
                fixture
                    .queryForMap("SELECT * FROM seckill_quota_settlement")
                    .get("redis_revision"));
            assertEquals(
                "block".equals(outcome) ? "5" : "6", fixtureRedis.opsForValue().get(keys.get(0)));
            assertEquals(
                "block".equals(outcome) ? "3" : "4", fixtureRedis.opsForValue().get(keys.get(4)));
            assertEquals("2", fixtureRedis.opsForValue().get(keys.get(5)));
            assertEquals(
                "block".equals(outcome)
                    ? "RESERVED|" + original.orderNo + "|1"
                    : "APPLIED|" + original.operationKey + "|2|4",
                fixtureRedis.opsForValue().get(keys.get(3)));
          });
    assertAll(assertions);
  }

  @Test
  void allAcceptedStatesKeepOriginalRecoveryWriteSetAndSnapshotSemantics() {
    for (String state : List.of("ACCEPTED", "PUBLISHED", "ORDER_CREATED"))
      for (String action : List.of("release", "block", "retry")) {
        reset();
        var original = row("RELEASE_PENDING", "owned-idem");
        seed(original);
        fixture.update("UPDATE seckill_reservation SET status=?", state);
        String owner = "owned-valid-writer-" + UUID.randomUUID();
        var occurredAt = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        assertEquals(
            1,
            realMapper.claim(
                original.orderNo,
                original.operationKey,
                2L,
                owner,
                occurredAt.plusMinutes(1),
                occurredAt));
        var before = facts();
        assertEquals(1, applyRecoveryWrite(action, original, owner, occurredAt));
        var after = facts();
        for (int tableIndex = 0; tableIndex < TABLES.size(); tableIndex++)
          if (tableIndex != 3) assertEquals(before.get(tableIndex), after.get(tableIndex));
        var stored = after.get(3).get(0);
        assertEquals(
            "release".equals(action)
                ? "RELEASED"
                : "block".equals(action) ? "PROJECTION_BLOCKED" : "RELEASE_PENDING",
            stored.get("status"));
        assertNull(stored.get("lease_owner"));
        assertNull(stored.get("lease_until"));
        if ("release".equals(action)) {
          assertEquals(4L, ((Number) stored.get("redis_revision")).longValue());
          assertNull(stored.get("last_error"));
          assertNull(stored.get("next_retry_at"));
        } else {
          assertNull(stored.get("redis_revision"));
          assertEquals("owned-write-test", stored.get("last_error"));
          assertNotNull(stored.get("next_retry_at"));
        }
        var beforeImmutable = new java.util.HashMap<>(before.get(3).get(0));
        var afterImmutable = new java.util.HashMap<>(stored);
        for (String changed :
            List.of(
                "status",
                "redis_revision",
                "last_error",
                "next_retry_at",
                "lease_owner",
                "lease_until",
                "updated_at")) {
          beforeImmutable.remove(changed);
          afterImmutable.remove(changed);
        }
        assertEquals(beforeImmutable, afterImmutable);
      }
  }

  /** 测试间仅清自有库，不重启中间件/改其它键。 */
  private static void reset() {
    org.mockito.Mockito.reset(observedMapper, observedRedis);
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
    fixture.update(
        "INSERT INTO"
            + " seckill_reservation(id,order_no,activity_id,sku_id,user_id,idempotency_key,status,created_at,updated_at)"
            + " VALUES(1,?,?,?,?,?,'ACCEPTED','1999-12-31','1999-12-31')",
        original.orderNo,
        original.activityId,
        original.skuId,
        original.userId,
        original.idempotencyKey);
  }

  @Test
  void changedClaimFactsOrLostLeaseCannotReleaseOldProjection() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String variant :
        List.of(
            "id",
            "order-case",
            "activity",
            "sku",
            "user",
            "idem",
            "quantity",
            "revision",
            "operation",
            "state",
            "state-case",
            "owner",
            "owner-case",
            "lease-null",
            "lease-expired",
            "created"))
      assertions.add(
          () -> {
            reset();
            var original = row("RELEASE_PENDING", "owned-idem");
            seed(original);
            var keys = prepareRedis(original);
            var redisBefore = redisFacts(keys);
            var snapshot = installClaimWriter(original, variant);
            queryResultSizes.clear();
            clearInvocations(observedMapper, observedRedis);
            assertAll(
                () -> assertEquals(0, recovery.recoverDue(), variant),
                () -> verifyNoInteractions(observedRedis),
                () -> assertEquals(snapshot.get(), facts(), "不修当前漂移事实/失租行: " + variant),
                () -> assertEquals(redisBefore, redisFacts(keys), variant));
            verify(observedMapper).findDue(any(), eq(50));
            verify(observedMapper)
                .claim(
                    eq(original.orderNo), eq(original.operationKey), eq(2L), any(), any(), any());
            verify(observedMapper).findSettlement(original.orderNo);
            verifyNoMoreInteractions(observedMapper);
          });
    assertAll(assertions);
  }

  @Test
  void healthyClaimFreshReadAllowsBlockedStateAndDiagnosticChanges() {
    for (String state : List.of("RELEASE_PENDING", "PROJECTION_BLOCKED")) {
      reset();
      var original = row(state, "😺".repeat(128));
      seed(original);
      var keys = prepareRedis(original);
      installClaimWriter(original, "healthy");
      queryResultSizes.clear();
      clearInvocations(observedMapper, observedRedis);
      assertEquals(1, recovery.recoverDue());
      verify(observedMapper).findSettlement(original.orderNo);
      verify(observedMapper).findDue(any(), eq(50));
      verify(observedMapper)
          .claim(eq(original.orderNo), eq(original.operationKey), eq(2L), any(), any(), any());
      verify(observedMapper)
          .markReleased(
              eq(original.orderNo),
              eq(original.operationKey),
              eq(2L),
              eq(4L),
              any(),
              any(SeckillQuotaSettlementPO.class));
      verifyNoMoreInteractions(observedMapper);
      assertReleased(original);
      assertSingleReleaseProjection(original, keys);
    }
  }

  @Test
  void recoveryUpdatesRejectAliasIdentityAndInvalidLeaseWithoutMutatingFacts() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String action : List.of("release", "block", "retry"))
      for (String variant :
          List.of(
              "owner-case",
              "owner-space",
              "order-case",
              "order-accent",
              "order-space",
              "operation-case",
              "operation-space",
              "state-case",
              "lease-null",
              "lease-expired",
              "operation-null",
              "owner-null",
              "different-owner",
              "different-state"))
        assertions.add(
            () -> {
              reset();
              var original = row("RELEASE_PENDING", "owned-idem");
              seed(original);
              String owner = "owned-worker-" + UUID.randomUUID();
              var occurredAt = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
              assertEquals(
                  1,
                  realMapper.claim(
                      original.orderNo,
                      original.operationKey,
                      2L,
                      owner,
                      occurredAt.plusMinutes(1),
                      occurredAt));
              switch (variant) {
                case "owner-case" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET lease_owner=?",
                        owner.toUpperCase(java.util.Locale.ROOT));
                case "owner-space" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET lease_owner=?", owner + " ");
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
                case "operation-case" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET operation_key=?",
                        original.operationKey.toUpperCase(java.util.Locale.ROOT));
                case "operation-space" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET operation_key=?",
                        original.operationKey + " ");
                case "state-case" ->
                    fixture.update("UPDATE seckill_quota_settlement SET status='release_pending'");
                case "lease-null" ->
                    fixture.update("UPDATE seckill_quota_settlement SET lease_until=NULL");
                case "lease-expired" ->
                    fixture.update("UPDATE seckill_quota_settlement SET lease_until='2000-01-01'");
                case "operation-null" ->
                    fixture.update("UPDATE seckill_quota_settlement SET operation_key=NULL");
                case "owner-null" ->
                    fixture.update("UPDATE seckill_quota_settlement SET lease_owner=NULL");
                case "different-owner" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET lease_owner='another-owned-worker'");
                case "different-state" ->
                    fixture.update("UPDATE seckill_quota_settlement SET status='CONFIRMED'");
                default -> throw new AssertionError(variant);
              }
              var before = facts();
              assertEquals(
                  0,
                  applyRecoveryWrite(action, original, owner, occurredAt),
                  action + ":" + variant);
              assertEquals(before, facts(), "原状态/租约/时间不得被修补");
            });
    assertAll(assertions);
  }

  @Test
  void healthyPendingAndBlockedExactUnexpiredLeaseKeepOriginalWriteSemantics() {
    for (String state : List.of("RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String action : List.of("release", "block", "retry")) {
        reset();
        var original = row(state, "😺".repeat(128));
        seed(original);
        String owner = "owned-worker-" + UUID.randomUUID();
        var occurredAt = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        assertEquals(
            1,
            realMapper.claim(
                original.orderNo,
                original.operationKey,
                2L,
                owner,
                occurredAt.plusMinutes(1),
                occurredAt));
        var before = facts();
        assertEquals(1, applyRecoveryWrite(action, original, owner, occurredAt));
        var after = facts();
        for (int tableIndex = 0; tableIndex < TABLES.size(); tableIndex++)
          if (tableIndex != 3) assertEquals(before.get(tableIndex), after.get(tableIndex));
        var stored =
            fixture.queryForMap(
                "SELECT * FROM seckill_quota_settlement WHERE order_no=?", original.orderNo);
        assertEquals(
            "release".equals(action)
                ? "RELEASED"
                : "block".equals(action) ? "PROJECTION_BLOCKED" : state,
            stored.get("status"));
        assertEquals(original.orderNo, stored.get("order_no"));
        assertEquals(original.operationKey, stored.get("operation_key"));
        assertEquals(original.idempotencyKey, stored.get("idempotency_key"));
        assertEquals(2L, ((Number) stored.get("revision")).longValue());
        assertNull(stored.get("lease_owner"));
        assertNull(stored.get("lease_until"));
        if ("release".equals(action)) {
          assertEquals(4L, ((Number) stored.get("redis_revision")).longValue());
          assertNull(stored.get("last_error"));
          assertNull(stored.get("next_retry_at"));
        } else {
          assertEquals("owned-write-test", stored.get("last_error"));
          assertNotNull(stored.get("next_retry_at"));
          assertNull(stored.get("redis_revision"));
        }
        var beforeImmutable = new java.util.HashMap<>(before.get(3).get(0));
        var afterImmutable = new java.util.HashMap<>(stored);
        for (String changed :
            List.of(
                "status",
                "redis_revision",
                "last_error",
                "next_retry_at",
                "lease_owner",
                "lease_until",
                "updated_at")) {
          beforeImmutable.remove(changed);
          afterImmutable.remove(changed);
        }
        assertEquals(beforeImmutable, afterImmutable);
      }
  }

  @Test
  void leaseChangedDuringLuaCannotBeMarkedOrClearedByOldRecovery() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String outcome : List.of("release", "block", "error"))
      assertions.add(
          () -> {
            reset();
            var original = row("RELEASE_PENDING", "owned-idem");
            seed(original);
            var keys = prepareRedis(original);
            if ("block".equals(outcome)) fixtureRedis.delete(keys.get(1));
            var snapshot =
                new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
            doAnswer(
                    invocation -> {
                      Object receipt = invocation.callRealMethod();
                      if ("block".equals(outcome))
                        fixture.update(
                            "UPDATE seckill_quota_settlement SET lease_until='2000-01-01'");
                      else {
                        String owner =
                            fixture.queryForObject(
                                "SELECT lease_owner FROM seckill_quota_settlement", String.class);
                        fixture.update(
                            "UPDATE seckill_quota_settlement SET lease_owner=?",
                            owner.toUpperCase(java.util.Locale.ROOT));
                      }
                      snapshot.set(facts());
                      if ("error".equals(outcome))
                        throw new org.springframework.data.redis.RedisConnectionFailureException(
                            "owned-post-lua-response-lost");
                      return receipt;
                    })
                .when(observedRedis)
                .execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class),
                    anyList(),
                    any(),
                    any(),
                    any(),
                    any());
            queryResultSizes.clear();
            clearInvocations(observedMapper, observedRedis);
            assertAll(
                () -> assertEquals(0, recovery.recoverDue(), outcome),
                () -> assertEquals(snapshot.get(), facts(), "不得修改失效租约: " + outcome));
            verify(observedRedis, times(1))
                .execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class),
                    anyList(),
                    eq(original.orderNo),
                    eq(original.operationKey),
                    eq("2"),
                    eq("1"));
            assertEquals(
                "block".equals(outcome) ? "5" : "6", fixtureRedis.opsForValue().get(keys.get(0)));
            assertEquals(
                "block".equals(outcome) ? "3" : "4", fixtureRedis.opsForValue().get(keys.get(4)));
            assertEquals("2", fixtureRedis.opsForValue().get(keys.get(5)));
          });
    assertAll(assertions);
  }

  @Test
  void immutableTupleWriteBindingRejectsChangedFactsOrMissingOriginal() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String action : List.of("release", "block", "retry"))
      for (String variant :
          List.of(
              "id",
              "activity",
              "sku",
              "user",
              "idem-case",
              "idem-space",
              "quantity",
              "created",
              "missing-original",
              "missing-created",
              "snapshot-order",
              "snapshot-operation",
              "snapshot-revision",
              "snapshot-id-null"))
        assertions.add(
            () -> {
              reset();
              var original = row("RELEASE_PENDING", "owned-idem");
              seed(original);
              String owner = "owned-worker-" + UUID.randomUUID();
              var occurredAt = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
              assertEquals(
                  1,
                  realMapper.claim(
                      original.orderNo,
                      original.operationKey,
                      2L,
                      owner,
                      occurredAt.plusMinutes(1),
                      occurredAt));
              var expected = realMapper.findSettlement(original.orderNo);
              switch (variant) {
                case "id" -> fixture.update("UPDATE seckill_quota_settlement SET id=2");
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
                case "created" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET created_at='2000-01-01 00:00:00.001'");
                case "missing-original" -> expected = null;
                case "missing-created" -> expected.createdAt = null;
                case "snapshot-order" -> expected.orderNo = "other-owned-order";
                case "snapshot-operation" -> expected.operationKey = original.orderNo + ":CONFIRM";
                case "snapshot-revision" -> expected.revision = 3L;
                case "snapshot-id-null" -> expected.id = null;
                default -> throw new AssertionError(variant);
              }
              var before = facts();
              assertEquals(
                  0,
                  applyRecoveryWriteSnapshot(action, original, owner, occurredAt, expected),
                  action + ":" + variant);
              assertEquals(before, facts());
            });
    assertAll(assertions);
  }

  @Test
  void tupleChangedDuringLuaCannotBeMarkedBlockedOrRetriedAsOriginal() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String outcome : List.of("release", "block", "error"))
      assertions.add(
          () -> {
            reset();
            var original = row("RELEASE_PENDING", "owned-idem");
            seed(original);
            var keys = prepareRedis(original);
            if ("block".equals(outcome)) fixtureRedis.delete(keys.get(1));
            var snapshot =
                new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
            doAnswer(
                    invocation -> {
                      Object receipt = invocation.callRealMethod();
                      fixture.update("UPDATE seckill_quota_settlement SET sku_id=sku_id+1");
                      snapshot.set(facts());
                      if ("error".equals(outcome))
                        throw new org.springframework.data.redis.RedisConnectionFailureException(
                            "owned-tuple-response-lost");
                      return receipt;
                    })
                .when(observedRedis)
                .execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class),
                    anyList(),
                    any(),
                    any(),
                    any(),
                    any());
            queryResultSizes.clear();
            clearInvocations(observedMapper, observedRedis);
            assertAll(
                () -> assertEquals(0, recovery.recoverDue(), outcome),
                () -> assertEquals(snapshot.get(), facts(), outcome));
            verify(observedRedis, times(1))
                .execute(
                    any(org.springframework.data.redis.core.script.RedisScript.class),
                    anyList(),
                    eq(original.orderNo),
                    eq(original.operationKey),
                    eq("2"),
                    eq("1"));
            assertEquals(
                "block".equals(outcome) ? "5" : "6", fixtureRedis.opsForValue().get(keys.get(0)));
            assertEquals(
                "block".equals(outcome) ? "3" : "4", fixtureRedis.opsForValue().get(keys.get(4)));
            assertEquals("2", fixtureRedis.opsForValue().get(keys.get(5)));
          });
    assertAll(assertions);
  }

  /** 调用唯一新增snapshot的真实Mapper签名，不保留旧签名旁路。 */
  private static int applyRecoveryWriteSnapshot(
      String action,
      SeckillQuotaSettlementPO original,
      String owner,
      java.time.LocalDateTime occurredAt,
      SeckillQuotaSettlementPO expected) {
    return switch (action) {
      case "release" ->
          realMapper.markReleased(original.orderNo, original.operationKey, 2L, 4L, owner, expected);
      case "block" ->
          realMapper.markProjectionBlocked(
              original.orderNo,
              original.operationKey,
              2L,
              "owned-write-test",
              occurredAt.plusSeconds(10),
              owner,
              expected);
      case "retry" ->
          realMapper.retry(
              original.orderNo,
              original.operationKey,
              2L,
              owner,
              "owned-write-test",
              occurredAt.plusSeconds(10),
              occurredAt,
              expected);
      default -> throw new AssertionError(action);
    };
  }

  /** 调用既有真实SQL签名，不调用被测私有helper伪造affected rows。 */
  private static int applyRecoveryWrite(
      String action,
      SeckillQuotaSettlementPO original,
      String owner,
      java.time.LocalDateTime occurredAt) {
    return switch (action) {
      case "release" ->
          realMapper.markReleased(original.orderNo, original.operationKey, 2L, 4L, owner, original);
      case "block" ->
          realMapper.markProjectionBlocked(
              original.orderNo,
              original.operationKey,
              2L,
              "owned-write-test",
              occurredAt.plusSeconds(10),
              owner,
              original);
      case "retry" ->
          realMapper.retry(
              original.orderNo,
              original.operationKey,
              2L,
              owner,
              "owned-write-test",
              occurredAt.plusSeconds(10),
              occurredAt,
              original);
      default -> throw new AssertionError(action);
    };
  }

  /** 原claim确实取得lease后才受控提交当前事实变化，fresh query仍由真实MyBatis读取。 */
  private static java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>
      installClaimWriter(SeckillQuotaSettlementPO original, String variant) {
    var snapshot =
        new java.util.concurrent.atomic.AtomicReference<List<List<Map<String, Object>>>>();
    doAnswer(
            invocation -> {
              String orderNo = invocation.getArgument(0);
              String operationKey = invocation.getArgument(1);
              long revision = invocation.getArgument(2);
              String owner = invocation.getArgument(3);
              java.time.LocalDateTime leaseUntil = invocation.getArgument(4);
              java.time.LocalDateTime occurredAt = invocation.getArgument(5);
              assertEquals(
                  1,
                  realMapper.claim(orderNo, operationKey, revision, owner, leaseUntil, occurredAt));
              switch (variant) {
                case "id" -> fixture.update("UPDATE seckill_quota_settlement SET id=2");
                case "order-case" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET order_no=?",
                        original.orderNo.toUpperCase(java.util.Locale.ROOT));
                case "activity" ->
                    fixture.update("UPDATE seckill_quota_settlement SET activity_id=activity_id+1");
                case "sku" -> fixture.update("UPDATE seckill_quota_settlement SET sku_id=sku_id+1");
                case "user" ->
                    fixture.update("UPDATE seckill_quota_settlement SET user_id=user_id+1");
                case "idem" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET idempotency_key='other-owned-idem'");
                case "quantity" -> fixture.update("UPDATE seckill_quota_settlement SET quantity=2");
                case "revision" -> fixture.update("UPDATE seckill_quota_settlement SET revision=3");
                case "operation" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET operation_key=?",
                        original.orderNo + ":CONFIRM");
                case "state" ->
                    fixture.update("UPDATE seckill_quota_settlement SET status='CONFIRMED'");
                case "state-case" ->
                    fixture.update("UPDATE seckill_quota_settlement SET status='release_pending'");
                case "owner" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET lease_owner='another-owned-worker'");
                case "owner-case" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET lease_owner=?",
                        owner.toUpperCase(java.util.Locale.ROOT));
                case "lease-null" ->
                    fixture.update("UPDATE seckill_quota_settlement SET lease_until=NULL");
                case "lease-expired" ->
                    fixture.update("UPDATE seckill_quota_settlement SET lease_until='2000-01-01'");
                case "created" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET created_at='2000-01-01 00:00:00.001'");
                case "healthy" ->
                    fixture.update(
                        "UPDATE seckill_quota_settlement SET"
                            + " status='PROJECTION_BLOCKED',last_error='diagnostic-only',updated_at='2026-10-12'");
                default -> throw new AssertionError(variant);
              }
              snapshot.set(facts());
              return 1;
            })
        .when(observedMapper)
        .claim(any(), any(), anyLong(), any(), any(), any());
    return snapshot;
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
    original.id = 1L;
    original.createdAt = java.time.LocalDateTime.of(2000, 1, 1, 0, 0);
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
