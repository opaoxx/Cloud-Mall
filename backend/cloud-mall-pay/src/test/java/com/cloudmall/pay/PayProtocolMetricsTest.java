package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.config.PayDebitModeConfiguration;
import com.cloudmall.pay.controller.PayController;
import com.cloudmall.pay.controller.PayFailureController;
import com.cloudmall.pay.controller.PaySuccessController;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.*;
import com.cloudmall.pay.domain.vo.*;
import com.cloudmall.pay.feign.*;
import com.cloudmall.pay.mapper.*;
import com.cloudmall.pay.service.*;
import com.cloudmall.pay.service.PayProtocolMetrics.*;
import com.cloudmall.pay.service.impl.*;
import com.cloudmall.pay.service.impl.PayFailureServiceImpl;
import com.cloudmall.pay.service.impl.PaySuccessServiceImpl;
import com.cloudmall.pay.service.impl.PayWriteMonitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实原服务方法产生有限观察；Mapper、Feign、TC和短事务均为不联网替身。 */
class PayProtocolMetricsTest {
  /** 测试结束不向下一次调用泄漏认证、XID或本地事务。 */
  @AfterEach
  void clearContexts() {
    AuthContext.clear();
    RootContext.unbind();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void persistedRoutesIgnoreCurrentConfigurationAndCountTerminalReplay() {
    for (String persisted : List.of("LEGACY", "TCC")) {
      Fixture fixture = new Fixture();
      try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
        fixture.install(new PayProtocolMetrics(registry));
        fixture.payment.debitMode = persisted;
        when(fixture.mode.newPaymentMode()).thenReturn(persisted.equals("TCC") ? "LEGACY" : "TCC");
        AuthContext.set(7L, "USER");
        fixture.successController.success(Fixture.PAY);
        fixture.successController.success(Fixture.PAY);
        fixture.failureController.fail(Fixture.PAY);
        fixture.controller.callback("test-token", fixture.callback(true));
        assertEquals(
            2,
            count(
                registry,
                "cloudmall.pay.protocol.route",
                "mode",
                persisted,
                "entry",
                "MOCK_SUCCESS"));
        assertEquals(
            1,
            count(
                registry, "cloudmall.pay.protocol.route", "mode", persisted, "entry", "MOCK_FAIL"));
        assertEquals(
            1,
            count(
                registry, "cloudmall.pay.protocol.route", "mode", persisted, "entry", "CALLBACK"));
        verify(fixture.mode, never()).newPaymentMode();
        if (persisted.equals("TCC")) verify(fixture.publicTcc).failure(any());
        else verify(fixture.legacyFailure).fail(any());
        verify(fixture.records, times(9)).selectByPayNo(Fixture.PAY);
        verify(fixture.intents, times(4)).findByPayNo(Fixture.PAY);
      }
    }
  }

  @Test
  void invalidPersistentModeAndInvalidCallbackDoNotCreateRoutes() {
    for (String invalid : new String[] {null, "", "tcc", "TCC ", "other"}) {
      Fixture fixture = new Fixture();
      try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
        fixture.install(new PayProtocolMetrics(registry));
        fixture.payment.debitMode = invalid;
        AuthContext.set(7L, "USER");
        assertEquals(
            503,
            assertThrows(BizException.class, () -> fixture.successController.success(Fixture.PAY))
                .getHttpStatus());
        assertTrue(registry.getMeters().isEmpty());
        verifyNoInteractions(fixture.publicTcc, fixture.legacyFailure, fixture.logs);
      }
    }
    Fixture fixture = new Fixture();
    try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
      fixture.install(new PayProtocolMetrics(registry));
      PayCallbackDTO request = fixture.callback(true);
      request.amount = new BigDecimal("99.00");
      assertEquals(
          409,
          assertThrows(BizException.class, () -> fixture.controller.callback("test-token", request))
              .getHttpStatus());
      assertTrue(registry.getMeters().isEmpty());
      verifyNoInteractions(fixture.logs);
    }
  }

  @Test
  void legacyRecoveryUsesOnlyItsOriginalReturnAndValidatedMode() {
    String[][] cases = {
      {"SUCCESS", "DELIVERED"},
      {"FAILED", "FAILED"},
      {"REFUNDED", "REFUNDED"},
      {"REFUND_PENDING", "REFUND_PENDING"},
      {"INIT", "UNKNOWN"},
      {"ORDER_SYNC_UNKNOWN", "UNKNOWN"}
    };
    for (String[] sample : cases) {
      Fixture fixture = new Fixture();
      try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
        fixture.install(new PayProtocolMetrics(registry));
        fixture.legacyIntent.status = sample[0];
        when(fixture.intents.findByPayNo(Fixture.PAY)).thenReturn(fixture.legacyIntent);
        assertEquals(sample[0], fixture.legacy.process(Fixture.PAY));
        assertEquals(1, observation(registry, "LEGACY", "LEGACY_RECOVERY", sample[1]));
        verify(fixture.intents, times(2)).findByPayNo(Fixture.PAY);
        verify(fixture.records, times(2)).selectByPayNo(Fixture.PAY);
        verify(fixture.intents).claim(eq(Fixture.PAY), any(), any(), any());
        verifyNoMoreInteractions(fixture.intents, fixture.records);
        verifyNoInteractions(fixture.users, fixture.orders);
      }
    }
    Fixture missing = new Fixture();
    try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
      missing.install(new PayProtocolMetrics(registry));
      assertEquals("INIT", missing.legacy.process(Fixture.PAY));
      assertEquals(1, observation(registry, "UNKNOWN", "LEGACY_RECOVERY", "UNKNOWN"));
      missing.legacyIntent.debitMode = null;
      when(missing.intents.findByPayNo(Fixture.PAY)).thenReturn(missing.legacyIntent);
      assertThrows(BizException.class, () -> missing.legacy.process(Fixture.PAY));
      assertEquals(2, observation(registry, "UNKNOWN", "LEGACY_RECOVERY", "UNKNOWN"));
    }
  }

  @Test
  void initiateCountsProcessOnceAndUnresolvedEvidenceKeepsUnknown() throws Exception {
    Fixture legacy = new Fixture();
    try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
      legacy.install(new PayProtocolMetrics(registry));
      when(legacy.intentService.ensureIntent(any())).thenReturn(legacy.legacyIntent);
      when(legacy.intents.findByPayNo(Fixture.PAY)).thenReturn(legacy.legacyIntent);
      assertEquals("SUCCESS", legacy.legacy.initiate(legacy.view()));
      assertEquals(1, observation(registry, "LEGACY", "LEGACY_RECOVERY", "DELIVERED"));
      verify(legacy.intentService).ensureIntent(any());
    }
    for (boolean recovery : List.of(false, true)) {
      Fixture fixture = new Fixture();
      try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
        fixture.install(new PayProtocolMetrics(registry));
        fixture.settleOnRpc = false;
        when(fixture.receiptRows.selectByPayNo(Fixture.PAY)).thenReturn(null);
        if (!recovery) fixture.execution.xid = null;
        PayTccExecutionVO result =
            recovery
                ? fixture.coordinator.recover(Fixture.PAY)
                : fixture.coordinator.execute(fixture.dispatch());
        assertEquals("UNKNOWN", result.state);
        assertEquals(
            1, observation(registry, "TCC", recovery ? "TCC_RECOVERY" : "TCC_EXECUTE", "UNKNOWN"));
        assertEquals(
            0,
            observation(
                registry, "TCC", recovery ? "TCC_RECOVERY" : "TCC_EXECUTE", "MONEY_COMMITTED"));
        assertNull(RootContext.getXID());
      }
    }
  }

  @Test
  void sharedEvidenceKeepsMissingClaimedTerminalsAndConflictDistinct() {
    String[][] cases = {
      {"TRYING", "TRY_ACCEPTED", "TRYING", "TRYING"},
      {"CONFIRMED", "CONFIRMED", "COMMITTED", "MONEY_COMMITTED"},
      {"CANCELLED", "CANCELLED", "ROLLEDBACK", "MONEY_CANCELLED"},
      {"CONFIRMED", "CANCELLED", "CONFLICT", "CONFLICT"},
      {"CLAIMED", "CONFIRMED", "UNKNOWN", "EVIDENCE_MISSING"}
    };
    for (String[] sample : cases) {
      Fixture fixture = new Fixture();
      try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
        fixture.install(new PayProtocolMetrics(registry));
        fixture.resourceStates(sample[0], sample[1]);
        assertEquals(sample[2], fixture.evidence.classify(fixture.execution, fixture.userFact));
        assertEquals(1, observation(registry, "TCC", "TCC_RESOURCE_EVIDENCE", sample[3]));
        verify(fixture.receiptRows).selectByPayNo(Fixture.PAY);
        if (sample[0].equals("CLAIMED")) verifyNoInteractions(fixture.fences);
        else verify(fixture.fences).findByBranch(Fixture.XID, 11L);
        verifyNoMoreInteractions(fixture.receiptRows, fixture.fences);
      }
    }
    Fixture missing = new Fixture();
    try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
      missing.install(new PayProtocolMetrics(registry));
      assertEquals("UNKNOWN", missing.evidence.classify(missing.execution, null));
      when(missing.receiptRows.selectByPayNo(Fixture.PAY)).thenReturn(null);
      assertEquals("UNKNOWN", missing.evidence.classify(missing.execution, missing.userFact));
      assertEquals(2, observation(registry, "TCC", "TCC_RESOURCE_EVIDENCE", "EVIDENCE_MISSING"));
      missing.userFact.amount = "bad-original-payload";
      BizException conflict =
          assertThrows(
              BizException.class,
              () -> missing.evidence.classify(missing.execution, missing.userFact));
      assertEquals("PAY_TCC_EXECUTION_CONFLICT", conflict.getCode());
      assertEquals(1, observation(registry, "TCC", "TCC_RESOURCE_EVIDENCE", "CONFLICT"));
    }
  }

  @Test
  void initialAndRecoveryRpcObserveOnlyActualCallsAndOriginalXid() throws Exception {
    for (boolean recovery : List.of(false, true))
      for (boolean rollback : List.of(false, true))
        for (boolean responseLost : List.of(false, true)) {
          Fixture fixture = new Fixture();
          try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
            fixture.install(new PayProtocolMetrics(registry));
            fixture.resourceStates("TRYING", "TRY_ACCEPTED");
            fixture.settleOnRpc = false;
            if (recovery) {
              fixture.execution.decision = rollback ? "ROLLBACK" : "COMMIT";
              fixture.execution.state = "UNKNOWN";
            } else {
              fixture.execution.xid = null;
              if (rollback) fixture.prepared.state = "DECLINED";
            }
            if (responseLost) {
              if (rollback)
                doThrow(new Exception("synthetic lost rollback"))
                    .when(fixture.tc)
                    .rollback(Fixture.XID);
              else
                doThrow(new Exception("synthetic lost commit"))
                    .when(fixture.tc)
                    .commit(Fixture.XID);
            }
            PayTccExecutionVO result =
                recovery
                    ? fixture.coordinator.recover(Fixture.PAY)
                    : fixture.coordinator.execute(fixture.dispatch());
            assertEquals("TRYING", result.state);
            assertEquals(
                1,
                count(
                    registry,
                    "cloudmall.pay.tcc.decision.rpc",
                    "origin",
                    recovery ? "RECOVERY" : "INITIAL",
                    "decision",
                    rollback ? "ROLLBACK" : "COMMIT",
                    "result",
                    responseLost ? "EXCEPTION" : "RETURNED"));
            if (rollback) {
              verify(fixture.tc).rollback(Fixture.XID);
              verify(fixture.tc, never()).commit(any());
            } else {
              verify(fixture.tc).commit(Fixture.XID);
              verify(fixture.tc, never()).rollback(any());
            }
            if (recovery) {
              verify(fixture.tc, never()).begin();
              verifyNoInteractions(fixture.receiptService);
            } else {
              verify(fixture.tc).begin();
              verify(fixture.receiptService).prepare(any());
            }
            assertEquals(
                1,
                observation(registry, "TCC", recovery ? "TCC_RECOVERY" : "TCC_EXECUTE", "TRYING"));
            assertNull(RootContext.getXID());
          }
        }
  }

  @Test
  void beginUnknownAndKnownFundsUseOriginalReturnedVoAndEarlyReturnsSendNoRpc() throws Exception {
    Fixture begin = new Fixture();
    try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
      begin.install(new PayProtocolMetrics(registry));
      begin.execution.xid = null;
      doThrow(new Exception("synthetic begin loss")).when(begin.tc).begin();
      assertEquals("BEGIN_UNKNOWN", begin.coordinator.execute(begin.dispatch()).state);
      assertEquals(1, observation(registry, "TCC", "TCC_EXECUTE", "BEGIN_UNKNOWN"));
      assertEquals("BEGIN_UNKNOWN", begin.coordinator.recover(Fixture.PAY).state);
      assertEquals(1, observation(registry, "TCC", "TCC_RECOVERY", "BEGIN_UNKNOWN"));
      verify(begin.tc, never()).commit(any());
      verify(begin.tc, never()).rollback(any());
      verifyNoInteractions(begin.receiptService, begin.bridge);
    }
    for (String state : List.of("COMMITTED", "ROLLEDBACK", "NONE")) {
      Fixture fixture = new Fixture();
      try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
        fixture.install(new PayProtocolMetrics(registry));
        fixture.execution.state = state.equals("NONE") ? "UNKNOWN" : state;
        fixture.execution.decision = "NONE";
        assertEquals(
            state.equals("NONE") ? "TRYING" : state,
            fixture.coordinator.recover(Fixture.PAY).state);
        assertEquals(
            1,
            observation(
                registry,
                "TCC",
                "TCC_RECOVERY",
                state.equals("COMMITTED")
                    ? "MONEY_COMMITTED"
                    : state.equals("ROLLEDBACK") ? "MONEY_CANCELLED" : "TRYING"));
        verifyNoInteractions(fixture.tc, fixture.receiptService);
      }
    }
    for (boolean cancelled : List.of(false, true)) {
      Fixture fixture = new Fixture();
      try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
        fixture.install(new PayProtocolMetrics(registry));
        fixture.execution.xid = null;
        if (cancelled) fixture.prepared.state = "DECLINED";
        assertEquals(
            cancelled ? "ROLLEDBACK" : "COMMITTED",
            fixture.coordinator.execute(fixture.dispatch()).state);
        assertEquals(
            1,
            observation(
                registry, "TCC", "TCC_EXECUTE", cancelled ? "MONEY_CANCELLED" : "MONEY_COMMITTED"));
      }
    }
  }

  @Test
  void callerContextAndOriginalExceptionObjectArePreserved() {
    Fixture fixture = new Fixture();
    try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
      fixture.install(new PayProtocolMetrics(registry));
      RootContext.bind("caller-owned-xid");
      assertThrows(BizException.class, () -> fixture.coordinator.execute(fixture.dispatch()));
      assertEquals("caller-owned-xid", RootContext.getXID());
      assertThrows(BizException.class, () -> fixture.coordinator.recover(Fixture.PAY));
      assertEquals("caller-owned-xid", RootContext.getXID());
      assertThrows(IllegalStateException.class, () -> fixture.legacy.process(Fixture.PAY));
      assertThrows(IllegalStateException.class, () -> fixture.delivery.deliver(Fixture.PAY));
      assertEquals("caller-owned-xid", RootContext.getXID());
      verifyNoInteractions(
          fixture.executionStore, fixture.deliveryStore, fixture.intents, fixture.tc);
      RootContext.unbind();
      BizException original = new BizException("PAY_TCC_EXECUTION_CONFLICT", "original", 409);
      when(fixture.executionStore.claimOwned(any()))
          .thenAnswer(
              invocation -> {
                RootContext.bind("dependency-xid");
                throw original;
              });
      assertSame(
          original,
          assertThrows(BizException.class, () -> fixture.coordinator.execute(fixture.dispatch())));
      assertNull(RootContext.getXID());
      assertEquals(1, observation(registry, "TCC", "TCC_EXECUTE", "CONFLICT"));
    }
  }

  @Test
  void callbackClaimsReuseInsertResultAfterBindingAndCompleteAddsNoClaim() {
    for (int inserted : new int[] {1, 0, 2}) {
      Fixture fixture = new Fixture();
      try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
        fixture.install(new PayProtocolMetrics(registry));
        when(fixture.logs.insertReceived(any())).thenReturn(inserted);
        fixture.callbackReceipt.claim(fixture.view(), fixture.callback(true), Fixture.CALLBACK);
        assertEquals(
            1,
            count(
                registry,
                "cloudmall.pay.callback.claim",
                "mode",
                "LEGACY",
                "direction",
                "SUCCESS",
                "result",
                inserted == 1 ? "NEW" : inserted == 0 ? "REPLAY" : "UNKNOWN"));
        fixture.bound.callbackStatus = "SUCCESS";
        fixture.bound.processedAt = LocalDateTime.now();
        fixture.callbackReceipt.complete(fixture.view(), fixture.callback(true), Fixture.CALLBACK);
        fixture.callbackReceipt.complete(fixture.view(), fixture.callback(true), Fixture.CALLBACK);
        assertEquals(1, registry.getMeters().size());
        verify(fixture.logs).insertReceived(any());
        verify(fixture.logs, times(3)).lockedSelect(Fixture.CALLBACK);
        verify(fixture.records, times(3)).selectForUpdate(Fixture.PAY);
        verifyNoMoreInteractions(fixture.records, fixture.logs);
      }
    }
    Fixture conflict = new Fixture();
    try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
      conflict.install(new PayProtocolMetrics(registry));
      when(conflict.logs.insertReceived(any())).thenReturn(0);
      conflict.bound.payNo = "different-payment";
      assertEquals(
          "PAY_CALLBACK_CONFLICT",
          assertThrows(
                  BizException.class,
                  () ->
                      conflict.callbackReceipt.claim(
                          conflict.view(), conflict.callback(false), Fixture.CALLBACK))
              .getCode());
      assertEquals(
          1,
          count(
              registry,
              "cloudmall.pay.callback.claim",
              "mode",
              "LEGACY",
              "direction",
              "FAILURE",
              "result",
              "CONFLICT"));
      assertThrows(
          BizException.class,
          () -> conflict.callbackReceipt.claim(conflict.view(), null, Fixture.CALLBACK));
      assertEquals(1, registry.getMeters().size());
      conflict.payment.debitMode = "invalid";
      conflict.bound.payNo = Fixture.PAY;
      conflict.bound.callbackStatus = "RECEIVED_SUCCESS";
      conflict.callbackReceipt.claim(conflict.view(), conflict.callback(true), Fixture.CALLBACK);
      assertEquals(
          1,
          count(
              registry,
              "cloudmall.pay.callback.claim",
              "mode",
              "UNKNOWN",
              "direction",
              "SUCCESS",
              "result",
              "REPLAY"));
    }
  }

  @Test
  void invalidClaimRetainsItsOriginalFailureWithoutInventingModeOrDirection() {
    Fixture fixture = new Fixture();
    try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
      fixture.install(new PayProtocolMetrics(registry));
      PayCallbackDTO invalid = fixture.callback(true);
      invalid.amount = null;
      assertEquals(
          400,
          assertThrows(
                  BizException.class,
                  () -> fixture.callbackReceipt.claim(fixture.view(), invalid, Fixture.CALLBACK))
              .getHttpStatus());
      assertEquals(
          1,
          count(
              registry,
              "cloudmall.pay.callback.claim",
              "mode",
              "UNKNOWN",
              "direction",
              "SUCCESS",
              "result",
              "UNKNOWN"));
      assertThrows(
          BizException.class,
          () -> fixture.callbackReceipt.claim(fixture.view(), null, Fixture.CALLBACK));
      assertEquals(1, registry.getMeters().size());
      verifyNoInteractions(fixture.records, fixture.logs);
    }
  }

  @Test
  void deliveryUsesOnlyFinalReadAndPreservesFinishBeforeObservation() {
    String[][] cases = {
      {"SUCCESS", "DELIVERED"},
      {"REFUNDED", "REFUNDED"},
      {"REFUND_PENDING", "REFUND_PENDING"},
      {"ORDER_SYNC_UNKNOWN", "UNKNOWN"}
    };
    for (String[] sample : cases) {
      Fixture fixture = new Fixture();
      try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
        fixture.install(new PayProtocolMetrics(registry));
        fixture.job.state = sample[0];
        assertEquals(sample[0], fixture.delivery.deliver(Fixture.PAY).state);
        assertEquals(1, observation(registry, "TCC", "TCC_DELIVERY", sample[1]));
        verify(fixture.deliveryStore).ensure(Fixture.PAY);
        if (!List.of("SUCCESS", "REFUNDED").contains(sample[0])) {
          verify(fixture.deliveryStore).claim(eq(Fixture.PAY), any());
          verify(fixture.deliveryStore).read(Fixture.PAY);
        }
        verifyNoMoreInteractions(fixture.deliveryStore);
      }
    }
    Fixture fixture = new Fixture();
    try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
      fixture.install(new PayProtocolMetrics(registry));
      when(fixture.deliveryStore.ensure(Fixture.PAY)).thenReturn(null);
      assertNull(fixture.delivery.deliver(Fixture.PAY));
      when(fixture.deliveryStore.ensure(Fixture.PAY)).thenReturn(fixture.job);
      when(fixture.deliveryStore.claim(eq(Fixture.PAY), any())).thenReturn(fixture.job);
      RuntimeException original = new IllegalStateException("original proof unavailable");
      when(fixture.deliveryStore.proof(fixture.job)).thenThrow(original);
      doAnswer(
              invocation -> {
                assertEquals(1, observation(registry, "TCC", "TCC_DELIVERY", "UNKNOWN"));
                return null;
              })
          .when(fixture.deliveryStore)
          .finish(eq(Fixture.PAY), any(), eq(1), eq("IllegalStateException"));
      assertEquals("ORDER_SYNC_UNKNOWN", fixture.delivery.deliver(Fixture.PAY).state);
      assertEquals(2, observation(registry, "TCC", "TCC_DELIVERY", "UNKNOWN"));
      verify(fixture.deliveryStore)
          .finish(eq(Fixture.PAY), any(), eq(1), eq("IllegalStateException"));
      verifyNoInteractions(fixture.users, fixture.orders);
    }
  }

  @Test
  void observationRuntimeFailuresCannotReplaceBusinessResultsOrExceptionsButErrorsEscape() {
    MeterRegistry registry = mock(MeterRegistry.class);
    RuntimeException registrationFailure = new IllegalStateException("metrics registration");
    doThrow(registrationFailure)
        .when(registry)
        .counter("cloudmall.pay.protocol.route", "mode", "LEGACY", "entry", "MOCK_SUCCESS");
    doThrow(registrationFailure)
        .when(registry)
        .counter(
            "cloudmall.pay.protocol.observation",
            "mode",
            "TCC",
            "scope",
            "TCC_DELIVERY",
            "outcome",
            "CONFLICT");
    Fixture fixture = new Fixture();
    fixture.install(new PayProtocolMetrics(registry));
    AuthContext.set(7L, "USER");
    assertNotNull(fixture.successController.success(Fixture.PAY));
    verify(registry)
        .counter("cloudmall.pay.protocol.route", "mode", "LEGACY", "entry", "MOCK_SUCCESS");
    BizException original = new BizException("PAY_TCC_DELIVERY_CONFLICT", "original", 409);
    when(fixture.deliveryStore.ensure(Fixture.PAY)).thenThrow(original);
    assertSame(
        original, assertThrows(BizException.class, () -> fixture.delivery.deliver(Fixture.PAY)));
    verify(registry)
        .counter(
            "cloudmall.pay.protocol.observation",
            "mode",
            "TCC",
            "scope",
            "TCC_DELIVERY",
            "outcome",
            "CONFLICT");
    Counter counter = mock(Counter.class);
    doReturn(counter)
        .when(registry)
        .counter("cloudmall.pay.protocol.route", "mode", "LEGACY", "entry", "MOCK_SUCCESS");
    doThrow(new IllegalStateException("metrics increment")).when(counter).increment();
    assertNotNull(fixture.successController.success(Fixture.PAY));
    verify(counter).increment();
    verify(registry, times(2))
        .counter("cloudmall.pay.protocol.route", "mode", "LEGACY", "entry", "MOCK_SUCCESS");
    AssertionError fatal = new AssertionError("jvm error");
    doThrow(fatal).when(counter).increment();
    assertSame(
        fatal,
        assertThrows(AssertionError.class, () -> fixture.successController.success(Fixture.PAY)));
    verify(counter, times(2)).increment();
    verify(registry, times(3))
        .counter("cloudmall.pay.protocol.route", "mode", "LEGACY", "entry", "MOCK_SUCCESS");
    verifyNoMoreInteractions(registry, counter);
  }

  @Test
  void oldConstructorsRemainNoopAndFiniteTagsContainNoBusinessIdentity() {
    Fixture fixture = new Fixture();
    for (Object service :
        List.of(
            fixture.controller,
            fixture.legacy,
            fixture.evidence,
            fixture.coordinator,
            fixture.delivery,
            fixture.callbackReceipt))
      assertSame(PayProtocolMetrics.noop(), ReflectionTestUtils.getField(service, "metrics"));
    assertEquals("TRYING", fixture.evidence.classify(fixture.execution, fixture.userFact));
    try (CloseableTestRegistry registry = new CloseableTestRegistry()) {
      fixture.install(new PayProtocolMetrics(registry));
      fixture.evidence.classify(fixture.execution, fixture.userFact);
      fixture.legacy.process(Fixture.PAY);
      fixture.callbackReceipt.claim(fixture.view(), fixture.callback(true), Fixture.CALLBACK);
      registry
          .getMeters()
          .forEach(
              meter ->
                  meter
                      .getId()
                      .getTags()
                      .forEach(
                          tag -> {
                            assertTrue(
                                List.of("mode", "scope", "outcome", "direction", "result")
                                    .contains(tag.getKey()));
                            assertTrue(tag.getValue().matches("[A-Z_]+"));
                            assertFalse(tag.getValue().contains(Fixture.PAY));
                          }));
      assertNull(ReflectionTestUtils.getField(PayProtocolMetrics.noop(), "registry"));
      assertThrows(NullPointerException.class, () -> new PayProtocolMetrics(null));
    }
  }

  /** 未出现过的合法组合没有预注册meter；读取不生成新counter。 */
  static double count(MeterRegistry registry, String name, String... tags) {
    Counter counter = registry.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  /** 按一个scope读取，绝不跨scope合并分母。 */
  static double observation(MeterRegistry registry, String mode, String scope, String outcome) {
    return count(
        registry,
        "cloudmall.pay.protocol.observation",
        "mode",
        mode,
        "scope",
        scope,
        "outcome",
        outcome);
  }

  /** 1.9.17 registry提供close但不实现AutoCloseable；仅为测试资源语法补接口，继承原close。 */
  private static final class CloseableTestRegistry extends SimpleMeterRegistry
      implements AutoCloseable {}

  /** 同一受控装配供单元测试和端点测试复用，不带任何Spring扫描注解。 */
  static final class Fixture {
    /** 仅用于替身选择的synthetic支付号。 */
    static final String PAY = "pay-metrics-synthetic";

    /** 原RPC的synthetic XID，不得进入tag。 */
    static final String XID = "metrics-original-xid";

    /** 原回调键，不得进入tag。 */
    static final String CALLBACK = "metrics-callback-synthetic";

    /** 各业务依赖都是非委派、不联网Mockito替身。 */
    final PayRecordMapper records = mock(PayRecordMapper.class);

    /** 原意图依赖。 */
    final PayDebitIntentMapper intents = mock(PayDebitIntentMapper.class);

    /** 原回调日志依赖。 */
    final PayCallbackLogMapper logs = mock(PayCallbackLogMapper.class);

    /** 原receipt依赖。 */
    final PayTccReceiptMapper receiptRows = mock(PayTccReceiptMapper.class);

    /** 原Fence依赖。 */
    final PayTccFenceMapper fences = mock(PayTccFenceMapper.class);

    /** 外部User替身。 */
    final UserClient users = mock(UserClient.class);

    /** 外部Order替身。 */
    final OrderClient orders = mock(OrderClient.class);

    /** 外部User TCC替身。 */
    final UserTccClient userTcc = mock(UserTccClient.class);

    /** 原持久执行边界替身。 */
    final PayTccExecutionStore executionStore = mock(PayTccExecutionStore.class);

    /** 原持久交付边界替身。 */
    final PayTccDeliveryStore deliveryStore = mock(PayTccDeliveryStore.class);

    /** 原TC窄接口替身。 */
    final PayTccTransactionClient tc = mock(PayTccTransactionClient.class);

    /** 原Try替身。 */
    final PayTccReceiptService receiptService = mock(PayTccReceiptService.class);

    /** 原User桥替身。 */
    final PayTccBridgeService bridge = mock(PayTccBridgeService.class);

    /** 原公开TCC委托替身。 */
    final PayTccPaymentService publicTcc = mock(PayTccPaymentService.class);

    /** 原Legacy失败委托替身。 */
    final PayLegacyFailureService legacyFailure = mock(PayLegacyFailureService.class);

    /** 新支付配置，既有支付路由测试禁止调用。 */
    final PayDebitModeConfiguration mode = mock(PayDebitModeConfiguration.class);

    /** 原意图保存替身。 */
    final PayDebitIntentService intentService = mock(PayDebitIntentService.class);

    /** 不联网短事务替身。 */
    final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    /** 当前真实原支付读取样本。 */
    final PayRecordPO payment = new PayRecordPO();

    /** 当前真实原意图读取样本。 */
    final PayDebitIntentPO legacyIntent = new PayDebitIntentPO();

    /** 当前真实原execution读取样本。 */
    final PayTccExecutionPO execution = new PayTccExecutionPO();

    /** 当前真实原Pay资源读取样本。 */
    final PayTccReceiptPO payFact = new PayTccReceiptPO();

    /** 当前真实原Fence读取样本。 */
    final PayTccFencePO fence = new PayTccFencePO();

    /** 当前真实原User资源读取样本。 */
    final PayTccDispatchVO userFact = new PayTccDispatchVO();

    /** 原Try返回。 */
    final PayTccReceiptVO prepared = new PayTccReceiptVO();

    /** 当前原交付样本。 */
    final PayTccDeliveryPO job = new PayTccDeliveryPO();

    /** 当前原callback绑定样本。 */
    final PayCallbackLogPO bound = new PayCallbackLogPO();

    /** 可选择只返回RPC而不生成终态，证明RETURNED不等于成功。 */
    boolean settleOnRpc = true;

    /** 六类真实业务实现。 */
    final PayController controller = new PayController();

    /** 与新成功服务及旧回调入口共用的原监视器。 */
    final PayWriteMonitor writeMonitor = new PayWriteMonitor();

    /** 实际成功业务与HTTP入口，避免旧继承兼容桥。 */
    final PaySuccessServiceImpl successService;

    final PaySuccessController successController;

    /** 独立失败服务及入口，共用原授权依赖与指标。 */
    final PayFailureServiceImpl failureService;

    final PayFailureController failureController;

    /** 真实Legacy恢复方法。 */
    final PayConsistencyService legacy;

    /** 真实共享证据方法。 */
    final PayTccExecutionEvidence evidence = new PayTccExecutionEvidence(receiptRows, fences);

    /** 真实Coordinator方法。 */
    final PayTccCoordinatorServiceImpl coordinator =
        new PayTccCoordinatorServiceImpl(executionStore, receiptService, bridge, evidence, tc);

    /** 真实Delivery方法。 */
    final PayTccDeliveryServiceImpl delivery =
        new PayTccDeliveryServiceImpl(deliveryStore, userTcc, users, orders, "test-token");

    /** 真实callback claim/complete方法。 */
    final PayCallbackReceiptService callbackReceipt =
        new PayCallbackReceiptService(logs, records, new ObjectMapper());

    Fixture() {
      payment.payNo = PAY;
      payment.orderNo = "order-metrics-synthetic";
      payment.userId = 7L;
      payment.amount = new BigDecimal("10.00");
      payment.status = "SUCCESS";
      payment.debitMode = "LEGACY";
      legacyIntent.payNo = PAY;
      legacyIntent.orderNo = payment.orderNo;
      legacyIntent.userId = payment.userId;
      legacyIntent.amount = payment.amount;
      legacyIntent.debitMode = "LEGACY";
      legacyIntent.status = "SUCCESS";
      execution.payNo = PAY;
      execution.orderNo = payment.orderNo;
      execution.userId = payment.userId;
      execution.amount = payment.amount;
      execution.xid = XID;
      execution.operationKey = "tcc-debit:" + PAY;
      execution.ledgerKey = PAY;
      execution.state = "RUNNING";
      execution.decision = "NONE";
      payFact.payNo = PAY;
      payFact.orderNo = payment.orderNo;
      payFact.userId = payment.userId;
      payFact.amount = payment.amount;
      payFact.xid = XID;
      payFact.operationKey = execution.operationKey;
      payFact.ledgerKey = PAY;
      userFact.payNo = PAY;
      userFact.orderNo = payment.orderNo;
      userFact.userId = payment.userId;
      userFact.amount = "10.00";
      userFact.xid = XID;
      userFact.operationKey = execution.operationKey;
      userFact.ledgerKey = PAY;
      userFact.userBranchId = 22L;
      fence.xid = XID;
      fence.branchId = 11L;
      fence.actionName = PayTccReceiptLocalResource.ACTION_NAME;
      resourceStates("TRYING", "TRY_ACCEPTED");
      prepared.state = "TRYING";
      job.payNo = PAY;
      job.orderNo = payment.orderNo;
      job.userId = payment.userId;
      job.amount = payment.amount;
      job.xid = XID;
      job.state = "ORDER_SYNC_UNKNOWN";
      job.attempts = 1;
      bound.payNo = PAY;
      bound.callbackId = CALLBACK;
      bound.callbackStatus = "RECEIVED_SUCCESS";
      bound.payload = "{}";
      when(records.selectByPayNo(PAY)).thenReturn(List.of(payment));
      when(records.selectForUpdate(PAY)).thenReturn(List.of(payment));
      when(logs.markProcessed(any(), any(), any(), any())).thenReturn(1);
      when(logs.insertReceived(any())).thenReturn(1);
      when(logs.lockedSelect(CALLBACK)).thenReturn(bound);
      when(receiptRows.selectByPayNo(PAY)).thenReturn(payFact);
      when(fences.findByBranch(XID, 11L)).thenReturn(fence);
      when(executionStore.read(PAY)).thenReturn(execution);
      when(executionStore.claimOwned(any())).thenReturn(execution);
      when(executionStore.verifyDecisionSnapshot(any())).thenReturn(execution);
      when(executionStore.bindOwnedXid(any(), any()))
          .thenAnswer(
              invocation -> {
                execution.xid = invocation.getArgument(1);
                execution.state = "RUNNING";
                return execution;
              });
      when(executionStore.decideOwned(any(), any(), any()))
          .thenAnswer(
              invocation -> {
                execution.decision = invocation.getArgument(2);
                execution.state = execution.decision + "_REQUESTED";
                return execution;
              });
      org.mockito.stubbing.Answer<PayTccExecutionPO> observe =
          invocation -> {
            String state = invocation.getArgument(1);
            execution.state = execution.xid == null ? "BEGIN_UNKNOWN" : state;
            return execution;
          };
      when(executionStore.observeSnapshot(any(), any(), any())).thenAnswer(observe);
      when(executionStore.observeDecidedSnapshot(any(), any(), any())).thenAnswer(observe);
      when(receiptService.prepare(any())).thenReturn(prepared);
      when(bridge.dispatch(any())).thenReturn(userFact);
      when(bridge.recover(PAY)).thenReturn(userFact);
      when(deliveryStore.ensure(PAY)).thenReturn(job);
      when(deliveryStore.read(PAY)).thenReturn(job);
      when(transactionManager.getTransaction(any()))
          .thenAnswer(invocation -> new SimpleTransactionStatus());
      legacy =
          new PayConsistencyService(
              intentService, intents, records, users, orders, transactionManager);
      try {
        when(tc.begin())
            .thenAnswer(
                invocation -> {
                  RootContext.bind(XID);
                  return XID;
                });
        doAnswer(
                invocation -> {
                  if (settleOnRpc) resourceStates("CONFIRMED", "CONFIRMED");
                  return null;
                })
            .when(tc)
            .commit(XID);
        doAnswer(
                invocation -> {
                  if (settleOnRpc) resourceStates("CANCELLED", "CANCELLED");
                  return null;
                })
            .when(tc)
            .rollback(XID);
      } catch (Exception impossible) {
        throw new AssertionError(impossible);
      }
      ReflectionTestUtils.setField(controller, "orders", orders);
      ReflectionTestUtils.setField(controller, "users", users);
      ReflectionTestUtils.setField(controller, "payRecordMapper", records);
      ReflectionTestUtils.setField(controller, "payCallbackLogMapper", logs);
      ReflectionTestUtils.setField(controller, "legacyFailureService", legacyFailure);
      ReflectionTestUtils.setField(controller, "payConsistencyService", legacy);
      ReflectionTestUtils.setField(controller, "callbackReceipts", callbackReceipt);
      ReflectionTestUtils.setField(controller, "payDebitIntentMapper", intents);
      ReflectionTestUtils.setField(controller, "callbackToken", "test-token");
      @SuppressWarnings("unchecked")
      ObjectProvider<PayTccPaymentService> provider = mock(ObjectProvider.class);
      when(provider.getIfAvailable()).thenReturn(publicTcc);
      ReflectionTestUtils.setField(controller, "tccPaymentService", provider);
      ReflectionTestUtils.setField(controller, "writeMonitor", writeMonitor);
      successService =
          new PaySuccessServiceImpl(
              records, intents, legacy, provider, PayProtocolMetrics.noop(), writeMonitor);
      successController = new PaySuccessController(successService);
      failureService =
          new PayFailureServiceImpl(
              records, intents, legacyFailure, provider, PayProtocolMetrics.noop());
      failureController = new PayFailureController(failureService);
    }

    /** 单测显式接线；端点测试不用此方法，而由Spring mandatory字段注入。 */
    void install(PayProtocolMetrics metrics) {
      for (Object service :
          List.of(
              controller,
              successService,
              failureService,
              legacy,
              evidence,
              coordinator,
              delivery,
              callbackReceipt)) ReflectionTestUtils.setField(service, "metrics", metrics);
    }

    /** 原证据状态与官方Fence保持对应，不复制生产分类算法。 */
    void resourceStates(String payState, String userState) {
      payFact.state = payState;
      payFact.branchId = payState.equals("CLAIMED") ? null : 11L;
      userFact.state = userState;
      fence.status = payState.equals("CONFIRMED") ? 2 : payState.equals("CANCELLED") ? 3 : 1;
    }

    /** 真实公开入口使用的原视图。 */
    PayVO view() {
      PayVO view = new PayVO();
      view.payNo = PAY;
      view.userId = payment.userId;
      view.orderNo = payment.orderNo;
      view.amount = payment.amount;
      view.status = payment.status;
      return view;
    }

    /** 固定合法原callback载荷。 */
    PayCallbackDTO callback(boolean success) {
      PayCallbackDTO request = new PayCallbackDTO();
      request.payNo = PAY;
      request.callbackId = CALLBACK;
      request.userId = payment.userId;
      request.amount = payment.amount;
      request.success = success;
      return request;
    }

    /** 固定合法原初次TCC请求。 */
    PayTccDispatchDTO dispatch() {
      PayTccDispatchDTO request = new PayTccDispatchDTO();
      request.payNo = PAY;
      request.orderNo = payment.orderNo;
      request.userId = payment.userId;
      request.amount = payment.amount;
      return request;
    }
  }
}
