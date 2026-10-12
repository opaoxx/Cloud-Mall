package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementRecovery;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** 有界扫描必须跨批覆盖健康后项，不借坏事实写入腾出窗口。 */
class SeckillRecoveryScanFairnessTest {
  @Test
  void healthyRowBehindThreeFullBadPagesIsEventuallyRecovered() {
    var scenario = new Scenario(151, 151);
    for (int round = 0; round < 4; round++) {
      scenario.readCount = 0;
      assertEquals(round == 3 ? 1 : 0, scenario.recovery.recoverDue());
      assertTrue(scenario.readCount <= 50);
    }
    assertEquals(Set.of(151L), scenario.released);
    assertEquals(150, scenario.records.stream().filter(row -> row.revision == 1L).count());
    verify(scenario.redis, times(1))
        .execute(
            any(RedisScript.class),
            anyList(),
            eq("order-151"),
            eq("order-151:ROLLBACK"),
            eq("2"),
            eq("1"));
  }

  @Test
  void wrappedHeadCanRecoverAgainWithoutDuplicateWithinBatch() {
    var scenario = new Scenario(4, 4);
    assertEquals(1, scenario.recovery.recoverDue());
    scenario.records.get(0).revision = 2L;
    scenario.readCount = 0;
    assertEquals(1, scenario.recovery.recoverDue());
    assertTrue(scenario.readCount <= 50);
    assertEquals(Set.of(1L, 4L), scenario.released);
    verify(scenario.redis, times(2))
        .execute(any(RedisScript.class), anyList(), any(), any(), eq("2"), eq("1"));
  }

  @Test
  void leaseLosersDoNotPinTheNextHealthyPage() {
    var scenario = new Scenario(51, 51);
    for (var record : scenario.records) record.revision = 2L;
    for (long recordId = 1; recordId <= 50; recordId++) scenario.leaseLosers.add(recordId);
    assertEquals(0, scenario.recovery.recoverDue());
    scenario.readCount = 0;
    assertEquals(1, scenario.recovery.recoverDue());
    assertTrue(scenario.readCount <= 50);
    assertEquals(Set.of(51L), scenario.released);
    verify(scenario.redis, times(1))
        .execute(
            any(RedisScript.class),
            anyList(),
            eq("order-51"),
            eq("order-51:ROLLBACK"),
            eq("2"),
            eq("1"));
  }

  @Test
  void queryFailurePreservesProgressAndReadBudgetForRetry() {
    var scenario = new Scenario(101, 101);
    assertEquals(0, scenario.recovery.recoverDue());
    scenario.failNextQuery = true;
    assertEquals(0, scenario.recovery.recoverDue());
    scenario.readCount = 0;
    assertEquals(0, scenario.recovery.recoverDue());
    assertTrue(scenario.readCount <= 50);
    scenario.readCount = 0;
    assertEquals(1, scenario.recovery.recoverDue());
    assertTrue(scenario.readCount <= 50);
    assertEquals(Set.of(101L), scenario.released);
  }

  @Test
  void missingIdAndEmptyPageNeverCreateAnInvalidCursor() {
    var scenario = new Scenario(1, -1);
    var record = scenario.records.get(0);
    record.id = null;
    assertEquals(0, scenario.recovery.recoverDue());
    assertEquals(0, scenario.recovery.recoverDue());
    assertEquals(List.of("findDue", "findDue"), scenario.queryNames);
    scenario.records.clear();
    assertEquals(0, scenario.recovery.recoverDue());
    record.id = 1L;
    record.revision = 2L;
    scenario.records.add(record);
    assertEquals(1, scenario.recovery.recoverDue());
  }

  @Test
  void slowerConcurrentRoundCannotOverwriteLaterScanProgress() throws Exception {
    var scenario = new Scenario(101, 101);
    scenario.firstHeadStarted = new java.util.concurrent.CountDownLatch(1);
    scenario.releaseFirstHead = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var slow = executor.submit(() -> scenario.recovery.recoverDue());
      assertTrue(scenario.firstHeadStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(0, scenario.recovery.recoverDue());
      assertEquals(0, scenario.recovery.recoverDue());
      scenario.releaseFirstHead.countDown();
      assertEquals(0, slow.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(1, scenario.recovery.recoverDue());
      assertEquals(Set.of(101L), scenario.released);
    } finally {
      scenario.releaseFirstHead.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  /** 独立数据集按Mapper查询语义响应，不调用被测私有helper构造结果。 */
  private static final class Scenario {
    /** 按原id有序的持久候选。 */
    final List<SeckillQuotaSettlementPO> records = new ArrayList<>();

    /** 原租约竞争失败的id。 */
    final Set<Long> leaseLosers = new HashSet<>();

    /** 正常markReleased成功的原id。 */
    final Set<Long> released = new HashSet<>();

    /** 每次claim后读回所需的本次owner和lease。 */
    final java.util.Map<String, String> owners = new HashMap<>();

    final java.util.Map<String, LocalDateTime> leases = new HashMap<>();

    /** 本次查询物理返回行数，wrap也计入。 */
    int readCount;

    /** 查询种类用于核无ID/空页的首段语义，支持并发记录。 */
    final List<String> queryNames = java.util.Collections.synchronizedList(new ArrayList<>());

    /** 首个首段查询次数，只有并发病例会阻塞。 */
    final java.util.concurrent.atomic.AtomicInteger headQueries =
        new java.util.concurrent.atomic.AtomicInteger();

    /** 并发病例的读取已开始信号。 */
    java.util.concurrent.CountDownLatch firstHeadStarted;

    /** 并发病例仅延迟第一个读取的释放信号。 */
    java.util.concurrent.CountDownLatch releaseFirstHead;

    /** 下一条查询真实抛异常，测试之后恢复。 */
    boolean failNextQuery;

    /** 只模拟Lua回执，不声称此unit是真实Redis。 */
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    /** 本场景被测恢复器。 */
    final SeckillQuotaSettlementRecovery recovery;

    /** 准备有界数据集，只允许原健康id被完成。 */
    Scenario(int recordCount, long healthyId) {
      for (long recordId = 1; recordId <= recordCount; recordId++) {
        var row = new SeckillQuotaSettlementPO();
        row.id = recordId;
        row.orderNo = "order-" + recordId;
        row.activityId = 10L;
        row.skuId = 20L;
        row.userId = 30L;
        row.idempotencyKey = "original-" + recordId;
        row.quantity = 1;
        row.revision = recordId == healthyId ? 2L : 1L;
        row.status = "RELEASE_PENDING";
        row.operationKey = row.orderNo + ":ROLLBACK";
        row.createdAt = LocalDateTime.of(2000, 1, 1, 0, 0);
        records.add(row);
      }
      var mapper =
          mock(
              SeckillQuotaSettlementMapper.class,
              invocation -> {
                String method = invocation.getMethod().getName();
                if ("findDue".equals(method) || "findDueAfter".equals(method)) {
                  queryNames.add(method);
                  if ("findDue".equals(method)
                      && firstHeadStarted != null
                      && headQueries.getAndIncrement() == 0) {
                    firstHeadStarted.countDown();
                    if (!releaseFirstHead.await(10, java.util.concurrent.TimeUnit.SECONDS))
                      throw new IllegalStateException("owned-latch-timeout");
                  }
                  if (failNextQuery) {
                    failNextQuery = false;
                    throw new IllegalStateException("owned-query-failure");
                  }
                  Long cursor = "findDueAfter".equals(method) ? invocation.getArgument(1) : null;
                  int limit = invocation.getArgument(invocation.getArguments().length - 1);
                  var due =
                      records.stream()
                          .filter(row -> !released.contains(row.id))
                          .filter(row -> cursor == null || row.id > cursor)
                          .limit(limit)
                          .toList();
                  readCount += due.size();
                  return due;
                }
                if ("claim".equals(method)) {
                  String orderNo = invocation.getArgument(0);
                  long recordId = Long.parseLong(orderNo.substring(6));
                  if (leaseLosers.contains(recordId)) return 0;
                  owners.put(orderNo, invocation.getArgument(3));
                  leases.put(orderNo, invocation.getArgument(4));
                  return 1;
                }
                if ("findSettlement".equals(method)) {
                  String orderNo = invocation.getArgument(0);
                  return records.stream()
                      .filter(row -> orderNo.equals(row.orderNo))
                      .map(
                          row -> {
                            var current = new SeckillQuotaSettlementPO();
                            current.id = row.id;
                            current.orderNo = row.orderNo;
                            current.activityId = row.activityId;
                            current.skuId = row.skuId;
                            current.userId = row.userId;
                            current.idempotencyKey = row.idempotencyKey;
                            current.quantity = row.quantity;
                            current.status = row.status;
                            current.revision = row.revision;
                            current.operationKey = row.operationKey;
                            current.createdAt = row.createdAt;
                            current.leaseOwner = owners.get(orderNo);
                            current.leaseUntil = leases.get(orderNo);
                            return current;
                          })
                      .findFirst()
                      .orElse(null);
                }
                if ("findMatchingAcceptanceId".equals(method)) {
                  var original = invocation.getArgument(0, SeckillQuotaSettlementPO.class);
                  assertNotNull(original);
                  assertEquals(original.orderNo + ":ROLLBACK", original.operationKey);
                  return 1L;
                }
                if ("markReleased".equals(method)) {
                  String orderNo = invocation.getArgument(0);
                  released.add(Long.parseLong(orderNo.substring(6)));
                  var original = invocation.getArgument(5, SeckillQuotaSettlementPO.class);
                  assertEquals(Long.valueOf(orderNo.substring(6)), original.id);
                  assertEquals(orderNo, original.orderNo);
                  assertEquals(orderNo + ":ROLLBACK", original.operationKey);
                  return 1;
                }
                throw new AssertionError("未预期的Mapper动作: " + method);
              });
      when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
          .thenReturn(7L);
      recovery = new SeckillQuotaSettlementRecovery(mapper, redis);
    }
  }
}
