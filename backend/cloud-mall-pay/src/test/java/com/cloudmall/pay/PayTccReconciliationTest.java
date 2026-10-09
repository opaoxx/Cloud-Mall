package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.po.PayTccFencePO;
import com.cloudmall.pay.domain.po.PayTccReceiptPO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.impl.PayTccReconciliationReadService;
import com.cloudmall.pay.service.impl.PayTccReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 本地取证入口门、固定分类和不输出原始证据标识的行为验证。 */
class PayTccReconciliationTest {
  @Test
  void disabledUnauthorizedAndInvalidRequestsNeverEnterTheTransactionalReader() {
    var reader = mock(PayTccReconciliationReadService.class);
    var disabled = new PayTccReconciliationService(reader, false, "ops-secret");
    assertEquals(
        503,
        assertThrows(BizException.class, () -> disabled.inspect("pay1", null, null))
            .getHttpStatus());
    var service = new PayTccReconciliationService(reader, true, "ops-secret");
    for (String[] headers :
        new String[][] {
          {null, null},
          {"cm18-reconciliation", null},
          {"cm18-reconciliation", "bad"},
          {"other", "ops-secret"}
        })
      assertEquals(
          403,
          assertThrows(BizException.class, () -> service.inspect("pay1", headers[0], headers[1]))
              .getHttpStatus());
    for (String payNo : List.of("", "bad\nkey", "中文", "%", "a".repeat(65)))
      assertEquals(
          400,
          assertThrows(
                  BizException.class,
                  () -> service.inspect(payNo, "cm18-reconciliation", "ops-secret"))
              .getHttpStatus());
    verifyNoInteractions(reader);
  }

  @Test
  void anInheritedLocalTransactionOrXidCannotEnterTheReader() {
    var reader = mock(PayTccReconciliationReadService.class);
    var service = new PayTccReconciliationService(reader, true, "ops-secret");
    try {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .setActualTransactionActive(true);
      assertEquals(
          409,
          assertThrows(
                  BizException.class,
                  () -> service.inspect("pay1", "cm18-reconciliation", "ops-secret"))
              .getHttpStatus());
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.clear();
    }
    try {
      io.seata.core.context.RootContext.bind("inherited-xid");
      assertEquals(
          409,
          assertThrows(
                  BizException.class,
                  () -> service.inspect("pay1", "cm18-reconciliation", "ops-secret"))
              .getHttpStatus());
    } finally {
      io.seata.core.context.RootContext.unbind();
    }
    verifyNoInteractions(reader);
  }

  @Test
  void databaseErrorsHaveFixedUnavailableRepliesWithoutRawSecrets() {
    var reader = mock(PayTccReconciliationReadService.class);
    when(reader.inspect("pay1"))
        .thenThrow(new DataAccessResourceFailureException("jdbc:password=secret"));
    var failure =
        assertThrows(
            BizException.class,
            () ->
                new PayTccReconciliationService(reader, true, "ops-secret")
                    .inspect("pay1", "cm18-reconciliation", "ops-secret"));
    assertEquals(503, failure.getHttpStatus());
    assertEquals("PAY_TCC_RECONCILIATION_UNAVAILABLE", failure.getCode());
    assertFalse(failure.getMessage().contains("secret"));
  }

  @Test
  void absentAliasedOrMultiplePaymentsCannotReadTheOtherFiveSources() {
    for (String variant : List.of("absent", "alias", "multiple")) {
      Fixture fixture = new Fixture();
      if ("absent".equals(variant))
        when(fixture.payments.findReconciliationCandidates("pay1")).thenReturn(List.of());
      if ("alias".equals(variant)) fixture.payment.payNo = "PAY1";
      if ("multiple".equals(variant))
        when(fixture.payments.findReconciliationCandidates("pay1"))
            .thenReturn(List.of(fixture.payment, fixture.payment));
      assertEquals(
          "absent".equals(variant) ? 404 : 409,
          assertThrows(BizException.class, () -> fixture.reader.inspect("pay1")).getHttpStatus());
      verifyNoInteractions(
          fixture.executions, fixture.dispatches, fixture.receipts, fixture.fences, fixture.legacy);
    }
  }

  @Test
  void localCommittedAndCancelledFactsStillCannotAuthorizeAnExternalConclusion() throws Exception {
    for (String outcome : List.of("COMMITTED", "ROLLEDBACK")) {
      Fixture fixture = new Fixture();
      fixture.execution.state = outcome;
      fixture.execution.decision = "COMMITTED".equals(outcome) ? "COMMIT" : "ROLLBACK";
      fixture.receipt.state = "COMMITTED".equals(outcome) ? "CONFIRMED" : "CANCELLED";
      fixture.dispatch.state = fixture.receipt.state;
      fixture.fence.status = "COMMITTED".equals(outcome) ? 2 : 3;
      fixture.execution.lastError = "jdbc:password=not-for-output";
      var report = fixture.reader.inspect("pay1");
      assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification);
      assertFalse(report.automaticActionsAllowed);
      assertEquals("PAY_LOCAL_ONLY", report.scope);
      assertEquals(List.of("USER", "ORDER", "STOCK", "TC", "MQ"), report.externalSystemsNotQueried);
      assertEquals(
          8 * 3600, OffsetDateTime.parse(report.collectedAt).getOffset().getTotalSeconds());
      String json = new ObjectMapper().writeValueAsString(report);
      assertFalse(json.contains(fixture.execution.xid));
      assertFalse(json.contains(fixture.execution.operationKey));
      assertFalse(json.contains("not-for-output"));
      assertEquals(64, report.identifiers.get("xidSha256").length());
      assertThrows(UnsupportedOperationException.class, () -> report.reasonCodes.add("mutated"));
      assertThrows(UnsupportedOperationException.class, () -> report.identifiers.put("raw", "bad"));
      fixture.verifyOnlyReads();
    }
  }

  @Test
  void beginUnknownIsBlockedWithoutBindingOrLookingUpAnInventedBranch() {
    Fixture fixture = new Fixture();
    fixture.execution.xid = null;
    fixture.execution.state = "BEGIN_UNKNOWN";
    fixture.execution.decision = "NONE";
    when(fixture.dispatches.selectByPayNo("pay1")).thenReturn(null);
    when(fixture.receipts.selectByPayNo("pay1")).thenReturn(null);
    var report = fixture.reader.inspect("pay1");
    assertEquals("BLOCKED_BEGIN_UNKNOWN", report.classification);
    assertNull(report.identifiers.get("xidSha256"));
    assertFalse(report.automaticActionsAllowed);
    verifyNoInteractions(fixture.fences);
  }

  @Test
  void eachMissingLocalAnchorProducesAVisibleReasonRatherThanSuccess() {
    for (String missing : List.of("execution", "dispatch", "receipt", "fence")) {
      Fixture fixture = new Fixture();
      switch (missing) {
        case "execution" -> when(fixture.executions.selectByPayNo("pay1")).thenReturn(null);
        case "dispatch" -> when(fixture.dispatches.selectByPayNo("pay1")).thenReturn(null);
        case "receipt" -> when(fixture.receipts.selectByPayNo("pay1")).thenReturn(null);
        case "fence" -> when(fixture.fences.findByBranch(anyString(), anyLong())).thenReturn(null);
        default -> throw new AssertionError();
      }
      var report = fixture.reader.inspect("pay1");
      assertEquals(
          "execution".equals(missing) ? "EXECUTION_MISSING" : "BLOCKED_LOCAL_EVIDENCE",
          report.classification);
      assertTrue(report.reasonCodes.contains(missing.toUpperCase() + "_ROW_MISSING"));
      assertFalse(report.automaticActionsAllowed);
    }
  }

  @Test
  void mismatchedTuplesBranchesFenceAndUnknownStatesAreLocalConflicts() throws Exception {
    for (String variant :
        List.of(
            "amount",
            "xid",
            "branch",
            "fence",
            "state",
            "mode",
            "legacy",
            "decision",
            "rollback-decision",
            "claim-decision",
            "begin-decision")) {
      Fixture fixture = new Fixture();
      switch (variant) {
        case "amount" -> fixture.receipt.amount = new BigDecimal("20.00");
        case "xid" -> fixture.dispatch.xid = "different-xid";
        case "branch" -> fixture.dispatch.userBranchId = fixture.receipt.branchId;
        case "fence" -> fixture.fence.actionName = "other-action";
        case "state" -> fixture.execution.state = "credential-in-state";
        case "mode" -> fixture.payment.debitMode = "tcc";
        case "decision" -> fixture.execution.state = "COMMIT_REQUESTED";
        case "rollback-decision" -> {
          fixture.execution.state = "ROLLBACK_REQUESTED";
          fixture.execution.decision = "COMMIT";
        }
        case "claim-decision" -> {
          fixture.execution.state = "CLAIMED";
          fixture.execution.decision = "COMMIT";
        }
        case "begin-decision" -> {
          fixture.execution.state = "BEGIN_UNKNOWN";
          fixture.execution.decision = "ROLLBACK";
        }
        case "legacy" -> {
          PayDebitIntentPO intent = new PayDebitIntentPO();
          when(fixture.legacy.findByPayNo("pay1")).thenReturn(intent);
        }
        default -> throw new AssertionError();
      }
      var report = fixture.reader.inspect("pay1");
      assertEquals("LOCAL_CONFLICT", report.classification, variant);
      assertFalse(new ObjectMapper().writeValueAsString(report).contains("credential-in-state"));
      assertFalse(report.automaticActionsAllowed);
    }
  }

  @Test
  void legacyIsNotReinterpretedAsTccAndAbsentExecutionStaysIncomplete() {
    Fixture fixture = new Fixture();
    fixture.payment.debitMode = "LEGACY";
    when(fixture.executions.selectByPayNo("pay1")).thenReturn(null);
    when(fixture.dispatches.selectByPayNo("pay1")).thenReturn(null);
    when(fixture.receipts.selectByPayNo("pay1")).thenReturn(null);
    assertEquals("LEGACY_NOT_INSPECTED", fixture.reader.inspect("pay1").classification);
    fixture.payment.debitMode = "TCC";
    assertEquals("BLOCKED_LOCAL_EVIDENCE", fixture.reader.inspect("pay1").classification);
    verifyNoInteractions(fixture.fences);
  }

  /** 明确的本地完整TRYING样本；只有六类SELECT允许被调用。 */
  private static final class Fixture {
    /** 支付候选只读替身。 */
    final PayRecordMapper payments = mock(PayRecordMapper.class);

    /** 执行事实只读替身。 */
    final PayTccExecutionMapper executions = mock(PayTccExecutionMapper.class);

    /** 派发事实只读替身。 */
    final PayTccDispatchMapper dispatches = mock(PayTccDispatchMapper.class);

    /** 分支事实只读替身。 */
    final PayTccReceiptMapper receipts = mock(PayTccReceiptMapper.class);

    /** Fence只读替身。 */
    final PayTccFenceMapper fences = mock(PayTccFenceMapper.class);

    /** Legacy意图只读替身。 */
    final PayDebitIntentMapper legacy = mock(PayDebitIntentMapper.class);

    /** 原支付样本。 */
    final PayRecordPO payment = new PayRecordPO();

    /** 原执行样本。 */
    final PayTccExecutionPO execution = new PayTccExecutionPO();

    /** 原派发样本。 */
    final PayTccDispatchPO dispatch = new PayTccDispatchPO();

    /** 原分支样本。 */
    final PayTccReceiptPO receipt = new PayTccReceiptPO();

    /** 原Fence样本。 */
    final PayTccFencePO fence = new PayTccFencePO();

    /** 实际分类器，外部资源全部为替身。 */
    final PayTccReconciliationReadService reader =
        new PayTccReconciliationReadService(
            payments, executions, dispatches, receipts, fences, legacy);

    Fixture() {
      payment.payNo = "pay1";
      payment.orderNo = "202610order1";
      payment.userId = 7L;
      payment.amount = new BigDecimal("10.00");
      payment.debitMode = "TCC";
      payment.status = "PENDING";
      execution.payNo = payment.payNo;
      execution.orderNo = payment.orderNo;
      execution.userId = payment.userId;
      execution.amount = payment.amount;
      execution.operationKey = "tcc-debit:pay1";
      execution.ledgerKey = "pay1";
      execution.xid = "private-tc:8091:original";
      execution.state = "RUNNING";
      execution.decision = "NONE";
      dispatch.payNo = payment.payNo;
      dispatch.orderNo = payment.orderNo;
      dispatch.userId = payment.userId;
      dispatch.amount = payment.amount;
      dispatch.operationKey = execution.operationKey;
      dispatch.ledgerKey = execution.ledgerKey;
      dispatch.xid = execution.xid;
      dispatch.state = "TRY_ACCEPTED";
      dispatch.userBranchId = 202L;
      receipt.payNo = payment.payNo;
      receipt.orderNo = payment.orderNo;
      receipt.userId = payment.userId;
      receipt.amount = payment.amount;
      receipt.operationKey = execution.operationKey;
      receipt.ledgerKey = execution.ledgerKey;
      receipt.xid = execution.xid;
      receipt.state = "TRYING";
      receipt.branchId = 101L;
      fence.xid = execution.xid;
      fence.branchId = receipt.branchId;
      fence.actionName = PayTccReceiptLocalResource.ACTION_NAME;
      fence.status = 1;
      when(payments.findReconciliationCandidates("pay1")).thenReturn(List.of(payment));
      when(executions.selectByPayNo("pay1")).thenReturn(execution);
      when(dispatches.selectByPayNo("pay1")).thenReturn(dispatch);
      when(receipts.selectByPayNo("pay1")).thenReturn(receipt);
      when(fences.findByBranch(execution.xid, receipt.branchId)).thenReturn(fence);
    }

    void verifyOnlyReads() {
      verify(payments).findReconciliationCandidates("pay1");
      verify(executions).selectByPayNo("pay1");
      verify(dispatches).selectByPayNo("pay1");
      verify(receipts).selectByPayNo("pay1");
      verify(fences).findByBranch(execution.xid, receipt.branchId);
      verify(legacy).findByPayNo("pay1");
      verifyNoMoreInteractions(payments, executions, dispatches, receipts, fences, legacy);
    }
  }
}
