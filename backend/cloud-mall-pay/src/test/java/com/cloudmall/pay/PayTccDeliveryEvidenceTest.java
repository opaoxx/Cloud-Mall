package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.pay.controller.PayTccDeliveryEvidenceController;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccCancelPO;
import com.cloudmall.pay.domain.po.PayTccDeliveryPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.po.PayTccFencePO;
import com.cloudmall.pay.domain.po.PayTccReceiptPO;
import com.cloudmall.pay.domain.vo.PayTccDeliveryReconciliationVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.impl.evidence.PayTccDeliveryEvidenceReadService;
import com.cloudmall.pay.service.impl.evidence.PayTccDeliveryEvidenceService;
import com.cloudmall.pay.service.impl.evidence.PayTccReconciliationReadService;
import com.cloudmall.pay.service.impl.evidence.PayTccReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 原八来源纯读取规则与上下文入口验证，不注入任何写入或远端资源。 */
class PayTccDeliveryEvidenceTest {
  @Test
  void allGatesRejectBeforeAnyTransactionalRead() {
    var local = mock(PayTccReconciliationReadService.class);
    var reader = mock(PayTccDeliveryEvidenceReadService.class);
    var gate = new PayTccReconciliationService(local, true, "ops-secret");
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () ->
                    new PayTccDeliveryEvidenceService(gate, reader, false)
                        .inspect("pay1", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () ->
                    new PayTccDeliveryEvidenceService(
                            new PayTccReconciliationService(local, false, "ops-secret"),
                            reader,
                            true)
                        .inspect("pay1", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    var service = new PayTccDeliveryEvidenceService(gate, reader, true);
    for (String[] headers :
        new String[][] {{null, null}, {"other", "ops-secret"}, {"cm18-reconciliation", "bad"}})
      assertEquals(
          403,
          assertThrows(BizException.class, () -> service.inspect("pay1", headers[0], headers[1]))
              .getHttpStatus());
    for (String invalid : List.of("", "bad\nkey", "中文", "%", "a".repeat(65)))
      assertEquals(
          400,
          assertThrows(
                  BizException.class,
                  () -> service.inspect(invalid, "cm18-reconciliation", "ops-secret"))
              .getHttpStatus());
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertEquals(
          409,
          assertThrows(
                  BizException.class,
                  () -> service.inspect("pay1", "cm18-reconciliation", "ops-secret"))
              .getHttpStatus());
    } finally {
      TransactionSynchronizationManager.clear();
    }
    try {
      RootContext.bind("inherited-xid");
      assertEquals(
          409,
          assertThrows(
                  BizException.class,
                  () -> service.inspect("pay1", "cm18-reconciliation", "ops-secret"))
              .getHttpStatus());
    } finally {
      RootContext.unbind();
    }
    verifyNoInteractions(local, reader);
  }

  @Test
  void diagnosticHttpErrorsAlwaysPreserveNoStoreAndTheUnifiedEnvelope() throws Exception {
    var local = mock(PayTccReconciliationReadService.class);
    var reader = mock(PayTccDeliveryEvidenceReadService.class);
    var gate = new PayTccReconciliationService(local, true, "ops-secret");
    var service = new PayTccDeliveryEvidenceService(gate, reader, true);
    var mvc =
        MockMvcBuilders.standaloneSetup(new PayTccDeliveryEvidenceController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    mvc.perform(get("/api/internal/payments/pay1/tcc-reconciliation/delivery-evidence"))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(
            get("/api/internal/payments/"
                    + "a".repeat(65)
                    + "/tcc-reconciliation/delivery-evidence")
                .header("X-Internal-Service", "cm18-reconciliation")
                .header("X-Internal-Callback-Token", "ops-secret"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Cache-Control", "no-store"));
    verifyNoInteractions(reader, local);
    for (int statusCode : List.of(404, 409)) {
      doThrow(new BizException("FIXTURE_READ_FAILURE", "固定错误", statusCode))
          .when(reader)
          .inspect("pay1");
      mvc.perform(
              get("/api/internal/payments/pay1/tcc-reconciliation/delivery-evidence")
                  .header("X-Internal-Service", "cm18-reconciliation")
                  .header("X-Internal-Callback-Token", "ops-secret"))
          .andExpect(status().is(statusCode))
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.code").value("FIXTURE_READ_FAILURE"));
    }
    doThrow(new DataAccessResourceFailureException("private-db-password"))
        .when(reader)
        .inspect("pay1");
    mvc.perform(
            get("/api/internal/payments/pay1/tcc-reconciliation/delivery-evidence")
                .header("X-Internal-Service", "cm18-reconciliation")
                .header("X-Internal-Callback-Token", "ops-secret"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.code").value("PAY_TCC_DELIVERY_EVIDENCE_UNAVAILABLE"));
    var disabled =
        MockMvcBuilders.standaloneSetup(
                new PayTccDeliveryEvidenceController(
                    new PayTccDeliveryEvidenceService(gate, reader, false)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    disabled
        .perform(
            get("/api/internal/payments/pay1/tcc-reconciliation/delivery-evidence")
                .header("X-Internal-Service", "cm18-reconciliation")
                .header("X-Internal-Callback-Token", "ops-secret"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.code").value("PAY_TCC_DELIVERY_EVIDENCE_DISABLED"));
  }

  @Test
  void databaseFailuresAreFixedAndDoNotExposeRawSecrets() {
    var local = mock(PayTccReconciliationReadService.class);
    var reader = mock(PayTccDeliveryEvidenceReadService.class);
    when(reader.inspect("pay1"))
        .thenThrow(new DataAccessResourceFailureException("jdbc:password=secret"));
    var service =
        new PayTccDeliveryEvidenceService(
            new PayTccReconciliationService(local, true, "ops-secret"), reader, true);
    var failure =
        assertThrows(
            BizException.class, () -> service.inspect("pay1", "cm18-reconciliation", "ops-secret"));
    assertEquals(503, failure.getHttpStatus());
    assertEquals("PAY_TCC_DELIVERY_EVIDENCE_UNAVAILABLE", failure.getCode());
    assertFalse(failure.getMessage().contains("secret"));
    verifyNoInteractions(local);
  }

  @Test
  void allLegitimateDeliveryStagesIncludingRefundRemainExternallyBlocked() {
    for (String stage :
        List.of(
            "READY",
            "CANCEL_REQUESTED",
            "CANCEL_RECONCILING",
            "ORDER_SYNC_UNKNOWN",
            "REFUND_PENDING",
            "SUCCESS",
            "REFUNDED")) {
      Fixture fixture = new Fixture();
      fixture.committed();
      fixture.delivery.state = stage;
      fixture.payment.status =
          List.of("READY", "CANCEL_REQUESTED", "CANCEL_RECONCILING").contains(stage)
              ? "PENDING"
              : stage;
      var report = fixture.inspect();
      assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification, stage);
      assertTrue(report.delivery.identityMatched());
      assertFalse(report.automaticActionsAllowed);
      assertEquals("COMMITTED", report.localEvidence.snapshot.execution().state());
      assertEquals("PAY_LOCAL_DELIVERY_CANCEL_ONLY", report.scope);
      assertEquals(List.of("USER", "ORDER", "STOCK", "TC", "MQ"), report.externalSystemsNotQueried);
      fixture.verifyOnlyReads();
      verify(fixture.deliveries).selectByPayNo("pay1");
      verify(fixture.cancels).selectByPayNo("pay1");
      verifyNoMoreInteractions(fixture.deliveries, fixture.cancels);
    }
  }

  @Test
  void deliveryIdentityStateAndProtocolConflictsAreNeverRepaired() {
    for (String variant :
        List.of(
            "pay",
            "user",
            "order",
            "amount",
            "nullAmount",
            "xid",
            "refund",
            "unknownState",
            "lowerState",
            "payment",
            "mode",
            "rollback")) {
      Fixture fixture = new Fixture();
      fixture.committed();
      switch (variant) {
        case "pay" -> fixture.delivery.payNo = "PAY1";
        case "user" -> fixture.delivery.userId = 8L;
        case "order" -> fixture.delivery.orderNo = "202610ORDER1";
        case "amount" -> fixture.delivery.amount = new BigDecimal("11");
        case "nullAmount" -> fixture.delivery.amount = null;
        case "xid" -> fixture.delivery.xid = "other-private-xid";
        case "refund" -> fixture.delivery.refundOperationKey = "refund:PAY1";
        case "unknownState" -> fixture.delivery.state = "password-private-state";
        case "lowerState" -> fixture.delivery.state = "ready";
        case "payment" -> fixture.payment.status = "SUCCESS";
        case "mode" -> fixture.payment.debitMode = "LEGACY";
        case "rollback" -> {
          fixture.execution.state = "ROLLEDBACK";
          fixture.execution.decision = "ROLLBACK";
          fixture.dispatch.state = "CANCELLED";
          fixture.receipt.state = "CANCELLED";
          fixture.fence.status = 3;
        }
        default -> throw new AssertionError(variant);
      }
      assertEquals("LOCAL_CONFLICT", fixture.inspect().classification, variant);
    }
  }

  @Test
  void missingDeliveryAndUnknownCommitRemainMissingEvidence() {
    Fixture missing = new Fixture();
    missing.committed();
    when(missing.deliveries.selectByPayNo("pay1")).thenReturn(null);
    var report = missing.inspect();
    assertEquals("BLOCKED_LOCAL_EVIDENCE", report.classification);
    assertTrue(report.reasonCodes.contains("DELIVERY_ROW_MISSING"));
    assertNull(report.delivery.identityMatched());
    for (String variant : List.of("UNKNOWN", "BEGIN_UNKNOWN", "missingExecution", "missingXid")) {
      Fixture fixture = new Fixture();
      fixture.committed();
      if ("missingExecution".equals(variant))
        when(fixture.executions.selectByPayNo("pay1")).thenReturn(null);
      else if ("missingXid".equals(variant)) {
        fixture.execution.xid = null;
        fixture.delivery.xid = null;
      } else {
        fixture.execution.state = variant;
        fixture.execution.decision = "BEGIN_UNKNOWN".equals(variant) ? "NONE" : "COMMIT";
      }
      var blocked = fixture.inspect();
      assertNotEquals("LOCAL_CONFLICT", blocked.classification, variant);
      assertTrue(
          blocked.reasonCodes.stream()
              .anyMatch(code -> code.startsWith("DELIVERY_") && code.endsWith("MISSING")),
          variant);
      assertFalse(blocked.automaticActionsAllowed);
    }
  }

  @Test
  void legitimatePreDebitCancellationDoesNotEraseBaseMissingEvidence() {
    for (String state :
        List.of("REQUESTED", "ORDER_CANCELLED", "CANCEL_UNKNOWN", "COMPLETED", "CONFLICT")) {
      Fixture fixture = new Fixture();
      fixture.cancellation(state);
      var report = fixture.inspect();
      assertEquals(
          "CONFLICT".equals(state) ? "LOCAL_CONFLICT" : "BLOCKED_LOCAL_EVIDENCE",
          report.classification,
          state);
      if ("CONFLICT".equals(state))
        assertTrue(report.reasonCodes.contains("CANCEL_RECORDED_CONFLICT"));
      assertTrue(report.cancel.identityMatched());
      assertFalse(report.delivery.present());
      assertTrue(report.reasonCodes.contains("EXECUTION_ROW_MISSING"));
      assertFalse(report.automaticActionsAllowed);
    }
  }

  @Test
  void cancellationRejectsWrongIdentityStatesAndAnyFundsCoexistence() {
    for (String variant :
        List.of(
            "pay",
            "user",
            "order",
            "amount",
            "state",
            "payment",
            "execution",
            "dispatch",
            "receipt",
            "legacy",
            "delivery")) {
      Fixture fixture = new Fixture();
      fixture.cancellation("REQUESTED");
      switch (variant) {
        case "pay" -> fixture.cancel.payNo = "PAY1";
        case "user" -> fixture.cancel.userId = 9L;
        case "order" -> fixture.cancel.orderNo = "wrong";
        case "amount" -> fixture.cancel.amount = null;
        case "state" -> fixture.cancel.state = "private-unknown";
        case "payment" -> fixture.payment.status = "FAILED";
        case "execution" ->
            when(fixture.executions.selectByPayNo("pay1")).thenReturn(fixture.execution);
        case "dispatch" ->
            when(fixture.dispatches.selectByPayNo("pay1")).thenReturn(fixture.dispatch);
        case "receipt" -> when(fixture.receipts.selectByPayNo("pay1")).thenReturn(fixture.receipt);
        case "legacy" ->
            when(fixture.legacy.findByPayNo("pay1")).thenReturn(new PayDebitIntentPO());
        case "delivery" -> fixture.committed();
        default -> throw new AssertionError(variant);
      }
      assertEquals("LOCAL_CONFLICT", fixture.inspect().classification, variant);
    }
  }

  @Test
  void reportIsImmutableRedactedAndPreservesObservationTimes() throws Exception {
    Fixture fixture = new Fixture();
    fixture.committed();
    var report = fixture.inspect();
    String json = new ObjectMapper().writeValueAsString(report);
    for (String secret :
        List.of(
            fixture.execution.xid,
            "refund:pay1",
            "private-owner-token",
            "private-error-password",
            "tcc-debit:pay1")) assertFalse(json.contains(secret), secret);
    assertEquals(report.localEvidence.identifiers.get("xidSha256"), report.delivery.xidSha256());
    assertTrue(report.delivery.refundKeySha256().matches("[0-9a-f]{64}"));
    assertEquals(
        ZoneOffset.ofHours(8), OffsetDateTime.parse(report.delivery.nextRetryAt()).getOffset());
    assertEquals(ZoneOffset.ofHours(8), OffsetDateTime.parse(report.collectedAt).getOffset());
    assertTrue(report.delivery.leasePresent());
    assertTrue(report.delivery.errorPresent());
    assertThrows(UnsupportedOperationException.class, () -> report.reasonCodes.add("fake"));
    fixture.delivery.state = "private-new-state";
    assertEquals("READY", report.delivery.state());
    assertEquals("UNRECOGNIZED", fixture.inspect().delivery.state());
    assertFalse(
        new ObjectMapper().writeValueAsString(fixture.inspect()).contains("private-new-state"));
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

    /** 交付台账纯读取替身。 */
    final PayTccDeliveryMapper deliveries = mock(PayTccDeliveryMapper.class);

    /** 取消台账纯读取替身。 */
    final PayTccCancelMapper cancels = mock(PayTccCancelMapper.class);

    /** 本地交付原载荷样本。 */
    final PayTccDeliveryPO delivery = new PayTccDeliveryPO();

    /** 扣款前取消原载荷样本。 */
    final PayTccCancelPO cancel = new PayTccCancelPO();

    /** 实际八来源分类器，未注入任何写入资源。 */
    final PayTccDeliveryEvidenceReadService extended =
        new PayTccDeliveryEvidenceReadService(reader, deliveries, cancels);

    PayTccDeliveryReconciliationVO inspect() {
      return extended.inspect("pay1");
    }

    void committed() {
      execution.state = "COMMITTED";
      execution.decision = "COMMIT";
      dispatch.state = "CONFIRMED";
      receipt.state = "CONFIRMED";
      fence.status = 2;
      delivery.payNo = payment.payNo;
      delivery.userId = payment.userId;
      delivery.orderNo = payment.orderNo;
      delivery.amount = payment.amount;
      delivery.xid = execution.xid;
      delivery.refundOperationKey = "refund:pay1";
      delivery.state = "READY";
      delivery.attempts = 5;
      delivery.nextRetryAt = LocalDateTime.of(2026, 10, 11, 12, 0);
      delivery.leaseOwner = "private-owner-token";
      delivery.leaseUntil = delivery.nextRetryAt.plusDays(1);
      delivery.lastError = "private-error-password";
      when(deliveries.selectByPayNo("pay1")).thenReturn(delivery);
    }

    void cancellation(String state) {
      when(executions.selectByPayNo("pay1")).thenReturn(null);
      when(dispatches.selectByPayNo("pay1")).thenReturn(null);
      when(receipts.selectByPayNo("pay1")).thenReturn(null);
      cancel.payNo = payment.payNo;
      cancel.userId = payment.userId;
      cancel.orderNo = payment.orderNo;
      cancel.amount = payment.amount;
      cancel.state = state;
      payment.status = "COMPLETED".equals(state) ? "FAILED" : "PENDING";
      when(cancels.selectByPayNo("pay1")).thenReturn(cancel);
    }

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
