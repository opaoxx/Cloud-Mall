package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccCancelPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccExecutionRecoveryMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.impl.PayTccCancelStore;
import com.cloudmall.pay.service.impl.PayTccDeliveryStore;
import com.cloudmall.pay.service.impl.PayTccExecutionEvidence;
import com.cloudmall.pay.service.impl.PayTccExecutionRecoveryStore;
import com.cloudmall.pay.service.impl.PayTccExecutionStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

/** 真实Store方法与Mapper参数捕获验证诊断前缀，不启动事务代理或数据库/TC。 */
class PayTccErrorUnicodeTest {
  /** 五个真实Store错误写入入口，取消阶段与取消收尾各自验证。 */
  private static final List<String> PATHS =
      List.of("CANCEL_STAGE", "CANCEL_FINISH", "EXECUTION", "DELIVERY", "RECOVERY");

  /** 512边界不得向Mapper交付孤立代理，五入口必须完整保留原emoji。 */
  @Test
  void boundaryErrorsRemainWholeAcrossTccStores() {
    assertAll(
        PATHS.stream()
            .map(
                path ->
                    (org.junit.jupiter.api.function.Executable)
                        () -> {
                          var scenario = scenario(path, "a".repeat(511) + "😀tail", 1, "valid");
                          scenario.operation.get();
                          assertNotNull(scenario.binding.get(), path);
                          assertEquals(
                              "a".repeat(511) + "😀",
                              scenario.binding.get().get(scenario.errorIndex),
                              path);
                        }));
  }

  /** 显式前缀期望核对真实调用的状态/身份/租约/退避参数，NULL与组合文本不归一化。 */
  @Test
  void prefixesAndWriteContractsRemainExact() {
    String[][] examples = {
      {null, null},
      {"", ""},
      {"a".repeat(511), "a".repeat(511)},
      {"a".repeat(512), "a".repeat(512)},
      {"a".repeat(513), "a".repeat(512)},
      {"界".repeat(513), "界".repeat(512)},
      {"😀".repeat(511), "😀".repeat(511)},
      {"😀".repeat(512), "😀".repeat(512)},
      {"😀".repeat(513), "😀".repeat(512)},
      {"a".repeat(511) + "😀tail", "a".repeat(511) + "😀"},
      {"😀".repeat(256) + "a".repeat(256) + "tail", "😀".repeat(256) + "a".repeat(256)},
      {"错".repeat(255) + "😀".repeat(257) + "tail", "错".repeat(255) + "😀".repeat(257)},
      {"é".repeat(257), "é".repeat(256)}
    };
    for (String path : PATHS)
      for (String[] example : examples) {
        var scenario = scenario(path, example[0], 1, "valid");
        var before = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        scenario.operation.get();
        var after = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        var binding = scenario.binding.get();
        assertNotNull(binding, path);
        assertEquals("pay-1", binding.get(0));
        assertEquals(example[1], binding.get(scenario.errorIndex));
        if (example[1] != null)
          assertDoesNotThrow(
              () ->
                  StandardCharsets.UTF_8
                      .newEncoder()
                      .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                      .encode(java.nio.CharBuffer.wrap((String) binding.get(scenario.errorIndex))));
        var writeTime = (LocalDateTime) binding.get(binding.size() - 1);
        if (path.equals("RECOVERY")) {
          assertEquals(0, writeTime.getNano() % 1_000_000);
          assertTrue(!writeTime.isBefore(before.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)));
        } else assertTrue(!writeTime.isBefore(before));
        assertTrue(!writeTime.isAfter(after));
        if (path.equals("EXECUTION")) assertEquals("COMMIT_REQUESTED", binding.get(1));
        else {
          assertEquals("token-1", binding.get(1));
          if (path.equals("CANCEL_STAGE")) assertEquals("CANCEL_UNKNOWN", binding.get(2));
          else if (path.equals("RECOVERY"))
            assertEquals(LocalDateTime.of(2027, 1, 1, 0, 0), binding.get(2));
          else assertEquals(writeTime.plusSeconds(5), binding.get(2));
        }
      }
  }

  /** 终态/失租拒绝仍不发更新，恢复CAS零行仍返回false，不因错误前缀授予写权限。 */
  @Test
  void rejectionAndTerminalRulesRemainUnchanged() {
    for (String mutation : List.of("wrong-token", "expired", "terminal")) {
      var scenario = scenario("CANCEL_STAGE", "😀".repeat(513), 1, mutation);
      assertEquals(false, scenario.operation.get());
      assertNull(scenario.binding.get());
    }
    var terminal = scenario("EXECUTION", "😀".repeat(513), 1, "terminal");
    assertNotNull(terminal.operation.get());
    assertNull(terminal.binding.get());
    var rejected = scenario("RECOVERY", "😀".repeat(513), 0, "valid");
    assertEquals(false, rejected.operation.get());
    assertEquals("😀".repeat(512), rejected.binding.get().get(3));
  }

  /** 构造合法原状态及替身Mapper，仅捕获真实public Store发出的参数，所有时间按应用时钟。 */
  private static Scenario scenario(String path, String error, int affectedRows, String mutation) {
    var binding = new AtomicReference<List<Object>>();
    Answer<Integer> capture =
        invocation -> {
          assertNull(binding.get(), "each operation must issue exactly one diagnostic write");
          binding.set(Arrays.asList(invocation.getArguments().clone()));
          return affectedRows;
        };
    var payments = mock(PayRecordMapper.class);
    var executions = mock(PayTccExecutionMapper.class);
    var cancels = mock(PayTccCancelMapper.class);
    var dispatches = mock(PayTccDispatchMapper.class);
    var receipts = mock(PayTccReceiptMapper.class);
    if (path.startsWith("CANCEL")) {
      var payment = new PayRecordPO();
      payment.payNo = "pay-1";
      payment.orderNo = "order-1";
      payment.userId = 7L;
      payment.amount = BigDecimal.TEN;
      payment.status = "PENDING";
      payment.debitMode = "TCC";
      var cancel = new PayTccCancelPO();
      cancel.payNo = payment.payNo;
      cancel.orderNo = payment.orderNo;
      cancel.userId = payment.userId;
      cancel.amount = payment.amount;
      cancel.state = mutation.equals("terminal") ? "COMPLETED" : "REQUESTED";
      cancel.leaseOwner = "token-1";
      cancel.leaseUntil = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusMinutes(10);
      if (mutation.equals("expired"))
        cancel.leaseUntil = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1);
      when(payments.selectForUpdate("pay-1")).thenReturn(List.of(payment));
      when(cancels.selectForUpdate("pay-1")).thenReturn(cancel);
      var store =
          new PayTccCancelStore(
              cancels,
              payments,
              executions,
              dispatches,
              receipts,
              mock(PayDebitIntentMapper.class));
      String token = mutation.equals("wrong-token") ? "other-token" : "token-1";
      if (path.equals("CANCEL_STAGE")) {
        doAnswer(capture).when(cancels).updateState(any(), any(), any(), any(), any());
        return new Scenario(() -> store.stage("pay-1", token, "CANCEL_UNKNOWN", error), binding, 3);
      }
      doAnswer(capture).when(cancels).finish(any(), any(), any(), any(), any());
      return new Scenario(
          () -> {
            store.finish("pay-1", token, 1, error);
            return null;
          },
          binding,
          3);
    }
    if (path.equals("EXECUTION")) {
      var execution = new PayTccExecutionPO();
      execution.payNo = "pay-1";
      execution.xid = "xid-1";
      execution.decision = "COMMIT";
      execution.state = mutation.equals("terminal") ? "COMMITTED" : "COMMIT_REQUESTED";
      when(executions.selectForUpdate("pay-1")).thenReturn(execution);
      when(executions.selectByPayNo("pay-1")).thenReturn(execution);
      doAnswer(capture).when(executions).updateObservation(any(), any(), any(), any());
      var store = new PayTccExecutionStore(executions, payments, dispatches, receipts, cancels);
      return new Scenario(() -> store.observe("pay-1", "UNKNOWN", error), binding, 2);
    }
    if (path.equals("DELIVERY")) {
      var deliveries = mock(PayTccDeliveryMapper.class);
      doAnswer(capture).when(deliveries).finishAttempt(any(), any(), any(), any(), any());
      var store =
          new PayTccDeliveryStore(
              deliveries, payments, executions, dispatches, mock(PayTccExecutionEvidence.class));
      return new Scenario(
          () -> {
            store.finish("pay-1", "token-1", 1, error);
            return null;
          },
          binding,
          3);
    }
    if (path.equals("RECOVERY")) {
      var recovery = mock(PayTccExecutionRecoveryMapper.class);
      doAnswer(capture).when(recovery).finishRecovery(any(), any(), any(), any(), any());
      var store = new PayTccExecutionRecoveryStore(recovery, executions, payments);
      return new Scenario(
          () -> store.finishRecovery("pay-1", "token-1", LocalDateTime.of(2027, 1, 1, 0, 0), error),
          binding,
          3);
    }
    throw new IllegalArgumentException("unknown diagnostic path");
  }

  /** 单次实际Store调用、捕获结果与错误参数的位置。 */
  private static final class Scenario {
    /** 调用真实public Store逻辑。 */
    final Supplier<Object> operation;

    /** Mapper唯一一次写入的原参数。 */
    final AtomicReference<List<Object>> binding;

    /** 错误字符串在Mapper形参中的位置。 */
    final int errorIndex;

    /** 创建独立调用场景，避免Mockito调用或状态在用例间共享。 */
    Scenario(Supplier<Object> operation, AtomicReference<List<Object>> binding, int errorIndex) {
      this.operation = operation;
      this.binding = binding;
      this.errorIndex = errorIndex;
    }
  }
}
