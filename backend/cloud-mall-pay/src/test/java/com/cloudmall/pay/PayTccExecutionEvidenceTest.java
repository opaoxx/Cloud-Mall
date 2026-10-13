package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.po.PayTccFencePO;
import com.cloudmall.pay.domain.po.PayTccReceiptPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayTccBridgeService;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.PayTccReceiptService;
import com.cloudmall.pay.service.PayTccTransactionClient;
import com.cloudmall.pay.service.impl.PayTccCoordinatorServiceImpl;
import com.cloudmall.pay.service.impl.PayTccExecutionEvidence;
import com.cloudmall.pay.service.impl.PayTccExecutionStore;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 真实Evidence及Coordinator逻辑，Mapper/Bridge/Store/TC为替身，不启动资金或事务链。 */
class PayTccExecutionEvidenceTest {
  /** 畸形金额应明确冲突，而不是NPE/NFE或缺一端UNKNOWN。 */
  @Test
  void missingOrMalformedMoneyIsConflict() {
    assertAll(
        List.of(
                "execution-null",
                "execution-amount-null",
                "execution-amount-null-missing-sides",
                "receipt-amount-null",
                "user-null",
                "",
                "not-a-number",
                " 10.00",
                "NaN",
                "1e2147483648",
                "1e-2147483649")
            .stream()
            .map(
                mutation ->
                    (org.junit.jupiter.api.function.Executable)
                        () -> {
                          var fixture = new EvidenceFixture();
                          switch (mutation) {
                            case "execution-null" -> fixture.execution = null;
                            case "execution-amount-null" -> fixture.execution.amount = null;
                            case "execution-amount-null-missing-sides" -> {
                              fixture.execution.amount = null;
                              fixture.user = null;
                              when(fixture.receipts.selectByPayNo(any())).thenReturn(null);
                            }
                            case "receipt-amount-null" -> fixture.pay.amount = null;
                            case "user-null" -> fixture.user.amount = null;
                            default -> fixture.user.amount = mutation;
                          }
                          var before = fixture.snapshot();
                          var failure =
                              assertThrows(
                                  BizException.class,
                                  () -> fixture.evidence.classify(fixture.execution, fixture.user),
                                  mutation);
                          assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
                          assertEquals(before, fixture.snapshot());
                        }));
  }

  /** 恢复入口不能把畸形资金载荷当暂不可用，不能写UNKNOWN或调用TC/新的Try。 */
  @Test
  void coordinatorDoesNotDowngradeMoneyConflictToUnknown() {
    for (String mutation : List.of("not-a-number", "receipt-null", "execution-null")) {
      var fixture = new EvidenceFixture();
      if (mutation.equals("receipt-null")) fixture.pay.amount = null;
      else if (mutation.equals("execution-null")) fixture.execution.amount = null;
      else fixture.user.amount = mutation;
      var store = mock(PayTccExecutionStore.class);
      var bridge = mock(PayTccBridgeService.class);
      var receiptService = mock(PayTccReceiptService.class);
      var transactions = mock(PayTccTransactionClient.class);
      when(store.read("pay-evidence-1")).thenReturn(fixture.execution);
      when(store.observe(any(), any(), any())).thenReturn(fixture.execution);
      when(bridge.recover("pay-evidence-1")).thenReturn(fixture.user);
      var coordinator =
          new PayTccCoordinatorServiceImpl(
              store, receiptService, bridge, fixture.evidence, transactions);
      var before = fixture.snapshot();
      var failure = assertThrows(BizException.class, () -> coordinator.recover("pay-evidence-1"));
      assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
      verify(store, never()).observe(any(), any(), any());
      verifyNoInteractions(transactions, receiptService);
      assertEquals(before, fixture.snapshot());
    }
  }

  /** 原数字等价、分支和Fence结论保持，不添加新的金额语法/scale或正数域。 */
  @Test
  void numericEquivalencePreservesExistingConclusions() {
    String[][] states = {
      {"CONFIRMED", "CONFIRMED", "2", "COMMITTED"},
      {"CANCELLED", "CANCELLED", "3", "ROLLEDBACK"},
      {"CANCELLED", "CANCELLED", "4", "ROLLEDBACK"},
      {"TRYING", "TRY_ACCEPTED", "1", "TRYING"},
      {"CONFIRMED", "CANCELLED", "2", "CONFLICT"},
      {"CANCELLED", "CONFIRMED", "3", "CONFLICT"},
      {"TRYING", "CONFIRMED", "1", "UNKNOWN"},
      {"CONFIRMED", "TRY_ACCEPTED", "2", "UNKNOWN"}
    };
    for (String[] state : states)
      for (String amount : List.of("10", "10.000", "+10.00", "1E+1", "0.1E2")) {
        var fixture = new EvidenceFixture();
        fixture.pay.state = state[0];
        fixture.user.state = state[1];
        fixture.fence.status = Integer.valueOf(state[2]);
        fixture.user.amount = amount;
        var before = fixture.snapshot();
        assertEquals(state[3], fixture.evidence.classify(fixture.execution, fixture.user));
        assertEquals(before, fixture.snapshot());
      }
    var cleanedFence = new EvidenceFixture();
    when(cleanedFence.fences.findByBranch(any(), any())).thenReturn(null);
    assertEquals(
        "COMMITTED", cleanedFence.evidence.classify(cleanedFence.execution, cleanedFence.user));
  }

  /** 现有身份/金额不等/分支和Fence冲突门仍拒绝，不因解析安全门放宽。 */
  @Test
  void existingIdentityAndFenceConflictsRemainRejected() {
    for (String mutation :
        List.of(
            "user-pay",
            "user-xid",
            "user-order",
            "user-id",
            "user-operation",
            "user-ledger",
            "user-amount",
            "pay-amount",
            "same-branch",
            "zero-branch",
            "null-branch",
            "fence-action",
            "fence-status",
            "trying-no-fence")) {
      var fixture = new EvidenceFixture();
      switch (mutation) {
        case "user-pay" -> fixture.user.payNo = "other-pay";
        case "user-xid" -> fixture.user.xid = "other-xid";
        case "user-order" -> fixture.user.orderNo = "other-order";
        case "user-id" -> fixture.user.userId = 8L;
        case "user-operation" -> fixture.user.operationKey = "other-operation";
        case "user-ledger" -> fixture.user.ledgerKey = "other-ledger";
        case "user-amount" -> fixture.user.amount = "11.00";
        case "pay-amount" -> fixture.pay.amount = new BigDecimal("11.00");
        case "same-branch" -> fixture.user.userBranchId = fixture.pay.branchId;
        case "zero-branch" -> fixture.user.userBranchId = 0L;
        case "null-branch" -> fixture.user.userBranchId = null;
        case "fence-action" -> fixture.fence.actionName = "other-action";
        case "fence-status" -> fixture.fence.status = 1;
        case "trying-no-fence" -> {
          fixture.pay.state = "TRYING";
          when(fixture.fences.findByBranch(any(), any())).thenReturn(null);
        }
      }
      var before = fixture.snapshot();
      var failure =
          assertThrows(
              BizException.class,
              () -> fixture.evidence.classify(fixture.execution, fixture.user),
              mutation);
      assertEquals("PAY_TCC_EXECUTION_CONFLICT", failure.getCode());
      assertEquals(before, fixture.snapshot());
    }
  }

  /** 整体缺少一端保持UNKNOWN，与已出现但畸形的金额分开处理。 */
  @Test
  void missingWholeSideRemainsUnknown() {
    var missingPay = new EvidenceFixture();
    when(missingPay.receipts.selectByPayNo(any())).thenReturn(null);
    assertEquals("UNKNOWN", missingPay.evidence.classify(missingPay.execution, missingPay.user));
    verifyNoInteractions(missingPay.fences);
    var missingUser = new EvidenceFixture();
    var before = missingUser.snapshot();
    assertEquals("UNKNOWN", missingUser.evidence.classify(missingUser.execution, null));
    assertEquals(before, missingUser.snapshot());
    var claimed = new EvidenceFixture();
    claimed.pay.state = "CLAIMED";
    claimed.pay.branchId = null;
    assertEquals("UNKNOWN", claimed.evidence.classify(claimed.execution, claimed.user));
    verifyNoInteractions(claimed.fences);
  }

  /** 独立完整两端载荷及查询替身，状态和输入对象均不在测试间共享。 */
  private static final class EvidenceFixture {
    /** 当前原execution金额与不可变身份。 */
    PayTccExecutionPO execution = new PayTccExecutionPO();

    /** 已校验User镜像，金额仍按原字符串传输。 */
    PayTccDispatchVO user = new PayTccDispatchVO();

    /** Pay永久receipt的原载荷。 */
    final PayTccReceiptPO pay = new PayTccReceiptPO();

    /** 原Pay branch对应Fence事实。 */
    final PayTccFencePO fence = new PayTccFencePO();

    /** 仅receipt读取替身。 */
    final PayTccReceiptMapper receipts = mock(PayTccReceiptMapper.class);

    /** 仅Fence读取替身。 */
    final PayTccFenceMapper fences = mock(PayTccFenceMapper.class);

    /** 真实业务证据核对逻辑。 */
    final PayTccExecutionEvidence evidence = new PayTccExecutionEvidence(receipts, fences);

    /** 构造金额10、不同两branch、同XID/所有固定键的完整事实。 */
    EvidenceFixture() {
      execution.payNo = "pay-evidence-1";
      execution.xid = "evidence-xid";
      execution.userId = 7L;
      execution.orderNo = "order-evidence-1";
      execution.amount = new BigDecimal("10.00");
      execution.operationKey = "tcc-debit:pay-evidence-1";
      execution.ledgerKey = execution.payNo;
      execution.state = "RUNNING";
      execution.decision = "NONE";
      user.payNo = execution.payNo;
      user.xid = execution.xid;
      user.userId = execution.userId;
      user.orderNo = execution.orderNo;
      user.operationKey = execution.operationKey;
      user.ledgerKey = execution.ledgerKey;
      user.amount = "10.00";
      user.state = "CONFIRMED";
      user.userBranchId = 22L;
      pay.payNo = execution.payNo;
      pay.xid = execution.xid;
      pay.userId = execution.userId;
      pay.orderNo = execution.orderNo;
      pay.amount = execution.amount;
      pay.operationKey = execution.operationKey;
      pay.ledgerKey = execution.ledgerKey;
      pay.state = "CONFIRMED";
      pay.branchId = 11L;
      fence.xid = execution.xid;
      fence.branchId = pay.branchId;
      fence.status = 2;
      fence.actionName = PayTccReceiptLocalResource.ACTION_NAME;
      when(receipts.selectByPayNo(any())).thenReturn(pay);
      when(fences.findByBranch(any(), any())).thenReturn(fence);
    }

    /** 对所有公开字段保存文本快照，包含金额文本和原金额scale，无写入或归一化。 */
    String snapshot() {
      return assertDoesNotThrow(
          () ->
              new com.fasterxml.jackson.databind.ObjectMapper()
                  .writeValueAsString(java.util.Arrays.asList(execution, user, pay, fence)));
    }
  }
}
