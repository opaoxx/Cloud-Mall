package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.impl.PayConsistencyService;
import com.cloudmall.pay.service.impl.PayDebitIntentService;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Service替身路径与SQL字面/绑定合同；不连接数据库，不证明实际扫描完成时长。 */
class PayRecoveryScanBoundaryTest {
  /** SQL合同的固定时间，仅检查绑定位置，不当数据库时区或精度实证。 */
  private static final LocalDateTime CONTRACT_TIME =
      LocalDateTime.of(2026, 10, 26, 12, 0, 0, 123000000);

  /** 仅替换候选读取和claim结果的意图Mapper。 */
  private PayDebitIntentMapper intents;

  /** 原支付载荷读取的替身。 */
  private PayRecordMapper payments;

  /** 无任何真实用户RPC的客户端替身。 */
  private UserClient users;

  /** 无任何真实订单RPC的客户端替身。 */
  private OrderClient orders;

  /** 不应被claim失败路径调用的意图保存服务。 */
  private PayDebitIntentService intentService;

  /** 不应被claim失败路径开启的事务替身。 */
  private PlatformTransactionManager transactions;

  /** 调度和claim测试调用的真实生产Service对象。 */
  private PayConsistencyService recovery;

  /** 各测试从无调用者本地/Seata事务上下文开始，不替生产方法清除caller上下文。 */
  @BeforeEach
  void prepareService() {
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertNull(RootContext.getXID());
    intents = mock(PayDebitIntentMapper.class);
    payments = mock(PayRecordMapper.class);
    users = mock(UserClient.class);
    orders = mock(OrderClient.class);
    intentService = mock(PayDebitIntentService.class);
    transactions = mock(PlatformTransactionManager.class);
    recovery =
        new PayConsistencyService(intentService, intents, payments, users, orders, transactions);
  }

  /** 测试不能遗留事务上下文给同一JVM中的其它回归。 */
  @AfterEach
  void verifyNoContextWasCreated() {
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertNull(RootContext.getXID());
  }

  /** 新到期低ID可被多个非空upper轮推迟，只有upper空时才回头；不证明等待秒数。 */
  @Test
  void newlyDueLowerIdWaitsAcrossNonEmptyUpperRoundsUntilWrap() {
    PayConsistencyService scanned = spy(recovery);
    Map<Long, PayDebitIntentPO> candidates = new TreeMap<>();
    for (long identifier : List.of(10L, 100L, 150L, 200L))
      candidates.put(identifier, intent(identifier, "INIT"));
    Set<Long> eligible = new HashSet<>(Set.of(100L));
    List<Long> queryCursors = new ArrayList<>();
    List<String> processed = new ArrayList<>();
    when(intents.findDueAfter(any(LocalDateTime.class), nullable(Long.class), anyInt()))
        .thenAnswer(
            invocation -> {
              LocalDateTime scanTime = invocation.getArgument(0);
              Long afterId = invocation.getArgument(1);
              int limit = invocation.getArgument(2);
              assertNotNull(scanTime);
              assertEquals(50, limit);
              queryCursors.add(afterId);
              // 仅DAO候选模型：eligible由测试轮次切换，不模拟SQL求值或真实到期时间。
              return candidates.entrySet().stream()
                  .filter(entry -> eligible.contains(entry.getKey()))
                  .filter(entry -> afterId == null || entry.getKey() > afterId)
                  .limit(limit)
                  .map(Map.Entry::getValue)
                  .toList();
            });
    doAnswer(
            invocation -> {
              String payNo = invocation.getArgument(0);
              PayDebitIntentPO selected =
                  candidates.values().stream()
                      .filter(candidate -> candidate.payNo.equals(payNo))
                      .findFirst()
                      .orElseThrow();
              processed.add(payNo);
              eligible.remove(selected.id);
              return "SUCCESS";
            })
        .when(scanned)
        .process(anyString());

    scanned.recoverDue();
    assertEquals(Arrays.asList((Long) null), queryCursors);
    assertEquals(List.of("pay-100"), processed);
    eligible.addAll(Set.of(10L, 150L));
    scanned.recoverDue();
    assertEquals(Arrays.asList(null, 100L), queryCursors);
    assertEquals(List.of("pay-100", "pay-150"), processed);
    eligible.add(200L);
    scanned.recoverDue();
    assertEquals(Arrays.asList(null, 100L, 150L), queryCursors);
    assertEquals(List.of("pay-100", "pay-150", "pay-200"), processed);

    // 上段非空的短批不补从头查询，低ID10只能到第四轮的第二次查询才被观察。
    scanned.recoverDue();
    assertEquals(Arrays.asList(null, 100L, 150L, 200L, null), queryCursors);
    assertEquals(List.of("pay-100", "pay-150", "pay-200", "pay-10"), processed);
    verify(scanned, times(4)).process(anyString());
    verify(intents, times(5)).findDueAfter(any(), nullable(Long.class), eq(50));
    verifyNoInteractions(users, orders, payments, intentService, transactions);
  }

  /** claim未取得只返回当前可见合法状态，非1不等同特定lease/due原因且不得发RPC。 */
  @Test
  void claimNotAcquiredReturnsCurrentIntentWithoutRpcOrFinancialWrites() {
    for (int claimRows : new int[] {0, -1, 2}) {
      for (String currentStatus : List.of("INIT", "ORDER_SYNC_UNKNOWN", "REFUND_PENDING")) {
        prepareService();
        PayDebitIntentPO pending = intent(7L, "INIT");
        PayDebitIntentPO current = intent(7L, currentStatus);
        PayRecordPO payment = new PayRecordPO();
        payment.payNo = pending.payNo;
        payment.orderNo = pending.orderNo;
        payment.userId = pending.userId;
        payment.amount = pending.amount;
        payment.debitMode = "LEGACY";
        payment.status = "PENDING";
        when(intents.findByPayNo(pending.payNo)).thenReturn(pending, current);
        when(payments.selectByPayNo(pending.payNo)).thenReturn(List.of(payment));
        when(intents.claim(eq(pending.payNo), anyString(), any(), any())).thenReturn(claimRows);

        assertEquals(currentStatus, recovery.process(pending.payNo));

        ArgumentCaptor<LocalDateTime> leaseUntil = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> claimTime = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(intents)
            .claim(eq(pending.payNo), anyString(), leaseUntil.capture(), claimTime.capture());
        assertEquals(claimTime.getValue().plusSeconds(30), leaseUntil.getValue());
        verify(intents, times(2)).findByPayNo(pending.payNo);
        verify(payments, times(2)).selectByPayNo(pending.payNo);
        verifyNoMoreInteractions(intents, payments);
        verifyNoInteractions(users, orders, intentService, transactions);
      }
    }
  }

  /** SQL_CONTRACT_ONLY：真实Mapper保留inclusive due/lease、严格id下界和参数位置。 */
  @Test
  void mapperScanSqlBindsInclusiveTimeAndStrictCursorWithoutDatabaseEvaluation() {
    AtomicReference<String> capturedSql = new AtomicReference<>();
    AtomicReference<Object[]> capturedParameters = new AtomicReference<>();
    JdbcTemplate jdbc =
        mock(
            JdbcTemplate.class,
            invocation -> {
              if (invocation.getMethod().getName().equals("query")) {
                Object[] arguments = invocation.getArguments();
                assertInstanceOf(RowMapper.class, arguments[1]);
                capturedSql.set((String) arguments[0]);
                capturedParameters.set(parameters(arguments, 2));
                return List.of();
              }
              return RETURNS_DEFAULTS.answer(invocation);
            });
    PayDebitIntentMapper mapper = new PayDebitIntentMapper(jdbc);
    for (Long afterId : Arrays.asList(null, 100L)) {
      assertTrue(mapper.findDueAfter(CONTRACT_TIME, afterId, 50).isEmpty());
      String sql = capturedSql.get().replaceAll("\\s+", " ");
      assertTrue(sql.contains("intent.next_retry_at<=?"));
      assertTrue(sql.contains("intent.lease_until IS NULL OR"));
      assertTrue(sql.contains("intent.lease_until<=?"));
      assertTrue(sql.contains("(? IS NULL OR intent.id>?) ORDER BY intent.id LIMIT ?"));
      assertFalse(sql.contains("intent.id>=?"));
      Object[] bound = capturedParameters.get();
      assertEquals(7, bound.length);
      assertInstanceOf(String.class, bound[0]);
      assertEquals(bound[0], bound[1]);
      assertArrayEquals(
          new Object[] {CONTRACT_TIME, CONTRACT_TIME, afterId, afterId, 50},
          Arrays.copyOfRange(bound, 2, bound.length));
    }
    Object[] lastBound = capturedParameters.get();
    verify(jdbc, times(2))
        .query(
            eq(capturedSql.get()),
            any(RowMapper.class),
            eq(lastBound[0]),
            eq(lastBound[1]),
            eq(CONTRACT_TIME),
            eq(CONTRACT_TIME),
            nullable(Long.class),
            nullable(Long.class),
            eq(50));
    verifyNoMoreInteractions(jdbc);
  }

  /** SQL_CONTRACT_ONLY：claim的更新时间、新lease和两处原比较时刻分别正确绑定。 */
  @Test
  void mapperClaimSqlBindsInclusiveDueAndLeaseWithoutInferringRejectionCause() {
    AtomicReference<String> capturedSql = new AtomicReference<>();
    AtomicReference<Object[]> capturedParameters = new AtomicReference<>();
    JdbcTemplate jdbc =
        mock(
            JdbcTemplate.class,
            invocation -> {
              if (invocation.getMethod().getName().equals("update")) {
                capturedSql.set(invocation.getArgument(0));
                capturedParameters.set(parameters(invocation.getArguments(), 1));
                return 0;
              }
              return RETURNS_DEFAULTS.answer(invocation);
            });
    PayDebitIntentMapper mapper = new PayDebitIntentMapper(jdbc);
    LocalDateTime newLease = CONTRACT_TIME.plusSeconds(30);

    assertEquals(0, mapper.claim("pay-contract", "claim-owner", newLease, CONTRACT_TIME));

    String sql = capturedSql.get().replaceAll("\\s+", " ");
    assertTrue(sql.startsWith("UPDATE pay_debit_intent SET lease_owner=?,lease_until=?"));
    assertTrue(sql.contains("next_retry_at<=?"));
    assertTrue(sql.contains("(lease_until IS NULL OR lease_until<=?)"));
    Object[] bound = capturedParameters.get();
    assertEquals(8, bound.length);
    assertArrayEquals(
        new Object[] {"claim-owner", newLease, CONTRACT_TIME, "pay-contract"},
        Arrays.copyOfRange(bound, 0, 4));
    assertInstanceOf(String.class, bound[4]);
    assertEquals(bound[4], bound[5]);
    assertEquals(CONTRACT_TIME, bound[6]);
    assertEquals(CONTRACT_TIME, bound[7]);
    verify(jdbc)
        .update(
            eq(capturedSql.get()),
            eq("claim-owner"),
            eq(newLease),
            eq(CONTRACT_TIME),
            eq("pay-contract"),
            eq(bound[4]),
            eq(bound[5]),
            eq(CONTRACT_TIME),
            eq(CONTRACT_TIME));
    verifyNoMoreInteractions(jdbc);
  }

  /** Mockito版本可能展开varargs；这里只规范已捕获的真实调用，不生成生产SQL。 */
  private static Object[] parameters(Object[] arguments, int offset) {
    if (arguments.length == offset + 1 && arguments[offset] instanceof Object[] values)
      return values.clone();
    return Arrays.copyOfRange(arguments, offset, arguments.length);
  }

  private static PayDebitIntentPO intent(long identifier, String status) {
    PayDebitIntentPO intent = new PayDebitIntentPO();
    intent.id = identifier;
    intent.payNo = "pay-" + identifier;
    intent.orderNo = "order-" + identifier;
    intent.userId = 7L;
    intent.amount = new BigDecimal("10.00");
    intent.status = status;
    intent.debitMode = "LEGACY";
    intent.attempts = 0;
    intent.nextRetryAt = CONTRACT_TIME;
    intent.createdAt = CONTRACT_TIME;
    intent.updatedAt = CONTRACT_TIME;
    return intent;
  }
}
