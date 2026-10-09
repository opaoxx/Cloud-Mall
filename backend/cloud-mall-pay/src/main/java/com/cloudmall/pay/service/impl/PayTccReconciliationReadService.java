package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.dto.PayTccEvidenceAnchorDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceCaptureDTO;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.po.PayTccFencePO;
import com.cloudmall.pay.domain.po.PayTccReceiptPO;
import com.cloudmall.pay.domain.vo.PayTccLocalFactVO;
import com.cloudmall.pay.domain.vo.PayTccReconciliationSnapshotVO;
import com.cloudmall.pay.domain.vo.PayTccReconciliationVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 只读本地RR快照，不依赖会产生观察写或RPC的高层恢复服务。 */
@Service
public class PayTccReconciliationReadService {
  /** 支付状态白名单。 */
  private static final Set<String> PAYMENT_STATES =
      Set.of(
          "PENDING",
          "ORDER_SYNC_UNKNOWN",
          "SUCCESS",
          "REFUND_PENDING",
          "REFUNDED",
          "FAILED",
          "CLOSED");

  /** 执行状态白名单。 */
  private static final Set<String> EXECUTION_STATES =
      Set.of(
          "CLAIMED",
          "BEGIN_UNKNOWN",
          "RUNNING",
          "COMMIT_REQUESTED",
          "ROLLBACK_REQUESTED",
          "UNKNOWN",
          "COMMITTED",
          "ROLLEDBACK",
          "CONFLICT");

  /** 派发状态白名单。 */
  private static final Set<String> DISPATCH_STATES =
      Set.of("PREPARED", "POST_ATTEMPTED", "TRY_UNKNOWN", "TRY_ACCEPTED", "CONFIRMED", "CANCELLED");

  /** Pay分支状态白名单。 */
  private static final Set<String> RECEIPT_STATES =
      Set.of("CLAIMED", "TRYING", "CONFIRMED", "CANCELLED");

  /** Legacy意图状态白名单。 */
  private static final Set<String> LEGACY_STATES =
      Set.of(
          "INIT",
          "DEBITED",
          "ORDER_SYNC_UNKNOWN",
          "SUCCESS",
          "REFUND_PENDING",
          "REFUNDED",
          "FAILED");

  /** 支付候选只读入口。 */
  private final PayRecordMapper payments;

  /** execution只读入口。 */
  private final PayTccExecutionMapper executions;

  /** dispatch只读入口。 */
  private final PayTccDispatchMapper dispatches;

  /** receipt只读入口。 */
  private final PayTccReceiptMapper receipts;

  /** Fence只读入口。 */
  private final PayTccFenceMapper fences;

  /** Legacy意图只读入口，与其他Mapper使用同一数据源。 */
  private final PayDebitIntentMapper legacyIntents;

  /** 创建六来源只读器，不初始化数据库或任何外部资源。 */
  public PayTccReconciliationReadService(
      PayRecordMapper payments,
      PayTccExecutionMapper executions,
      PayTccDispatchMapper dispatches,
      PayTccReceiptMapper receipts,
      PayTccFenceMapper fences,
      PayDebitIntentMapper legacyIntents) {
    this.payments = payments;
    this.executions = executions;
    this.dispatches = dispatches;
    this.receipts = receipts;
    this.fences = fences;
    this.legacyIntents = legacyIntents;
  }

  /** 在入口门之后读取同一快照；无行或身份不可信时拒绝，绝不读取另一个支付号。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PayTccReconciliationVO inspect(String payNo) {
    return readSnapshot(payNo).localReport();
  }

  /** 在同一独立RR事务返回内部锚点；事务结束后才允许查询User。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PayTccEvidenceCaptureDTO capture(String payNo) {
    return readSnapshot(payNo);
  }

  /** 六来源查询只执行一次；无可信原始事实时不生成查询锚点。 */
  private PayTccEvidenceCaptureDTO readSnapshot(String payNo) {
    // 1. 用有界候选核验原始支付号，不能因collation别名泄露另一笔支付。
    List<PayRecordPO> candidates = payments.findReconciliationCandidates(payNo);
    if (candidates.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "支付记录不存在", 404);
    if (candidates.size() != 1 || !Objects.equals(payNo, candidates.get(0).payNo))
      throw new BizException("PAY_TCC_RECONCILIATION_IDENTITY_CONFLICT", "支付候选身份不可信", 409);
    PayRecordPO payment = candidates.get(0);
    // 2. 所有读取均为无锁SELECT；Fence仅按本地receipt的原分支定位。
    PayTccExecutionPO execution = executions.selectByPayNo(payNo);
    PayTccDispatchPO dispatch = dispatches.selectByPayNo(payNo);
    PayTccReceiptPO receipt = receipts.selectByPayNo(payNo);
    PayTccFencePO fence =
        receipt != null && nonblank(receipt.xid) && positive(receipt.branchId)
            ? fences.findByBranch(receipt.xid, receipt.branchId)
            : null;
    PayDebitIntentPO legacy = legacyIntents.findByPayNo(payNo);
    // 3. 只判本地缺失/冲突；本地完整仍必须等待尚未查询的外部证据。
    List<String> conflicts = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    if (!Set.of("LEGACY", "TCC").contains(Objects.toString(payment.debitMode, ""))
        || !PAYMENT_STATES.contains(Objects.toString(payment.status, ""))
        || !positive(payment.userId)
        || !nonblank(payment.orderNo)
        || payment.amount == null
        || payment.amount.signum() <= 0) conflicts.add("PAYMENT_FACT_CONFLICT");
    if (execution != null) {
      tuple(
          payment,
          execution.payNo,
          execution.userId,
          execution.orderNo,
          execution.amount,
          execution.operationKey,
          execution.ledgerKey,
          "EXECUTION",
          conflicts);
      if (!EXECUTION_STATES.contains(Objects.toString(execution.state, ""))
          || !Set.of("NONE", "COMMIT", "ROLLBACK")
              .contains(Objects.toString(execution.decision, ""))
          || "CONFLICT".equals(execution.state)
          || ("COMMIT_REQUESTED".equals(execution.state) && !"COMMIT".equals(execution.decision))
          || ("ROLLBACK_REQUESTED".equals(execution.state)
              && !"ROLLBACK".equals(execution.decision))
          || (Set.of("CLAIMED", "BEGIN_UNKNOWN").contains(execution.state)
              && !"NONE".equals(execution.decision))
          || ("COMMITTED".equals(execution.state) && !"COMMIT".equals(execution.decision))
          || ("ROLLEDBACK".equals(execution.state) && "COMMIT".equals(execution.decision)))
        conflicts.add("EXECUTION_STATE_CONFLICT");
    }
    if (dispatch != null) {
      tuple(
          payment,
          dispatch.payNo,
          dispatch.userId,
          dispatch.orderNo,
          dispatch.amount,
          dispatch.operationKey,
          dispatch.ledgerKey,
          "DISPATCH",
          conflicts);
      if (!DISPATCH_STATES.contains(Objects.toString(dispatch.state, "")))
        conflicts.add("DISPATCH_STATE_CONFLICT");
    }
    if (receipt != null) {
      tuple(
          payment,
          receipt.payNo,
          receipt.userId,
          receipt.orderNo,
          receipt.amount,
          receipt.operationKey,
          receipt.ledgerKey,
          "RECEIPT",
          conflicts);
      if (!RECEIPT_STATES.contains(Objects.toString(receipt.state, "")))
        conflicts.add("RECEIPT_STATE_CONFLICT");
    }
    if (legacy != null
        && (!"LEGACY".equals(legacy.debitMode)
            || !Objects.equals(payment.payNo, legacy.payNo)
            || !Objects.equals(payment.userId, legacy.userId)
            || !Objects.equals(payment.orderNo, legacy.orderNo)
            || !sameAmount(payment.amount, legacy.amount)
            || !LEGACY_STATES.contains(Objects.toString(legacy.status, ""))))
      conflicts.add("LEGACY_INTENT_CONFLICT");
    boolean tcc = "TCC".equals(payment.debitMode);
    if ((!tcc && (execution != null || dispatch != null || receipt != null))
        || (tcc && legacy != null)) conflicts.add("MIXED_PROTOCOL_EVIDENCE");
    if (execution != null && nonblank(execution.xid)) {
      if (dispatch != null && !Objects.equals(execution.xid, dispatch.xid))
        conflicts.add("DISPATCH_XID_CONFLICT");
      if (receipt != null && !Objects.equals(execution.xid, receipt.xid))
        conflicts.add("RECEIPT_XID_CONFLICT");
    }
    if (receipt != null
        && dispatch != null
        && positive(receipt.branchId)
        && Objects.equals(receipt.branchId, dispatch.userBranchId))
      conflicts.add("BRANCH_ID_CONFLICT");
    if (receipt != null
        && dispatch != null
        && nonblank(receipt.xid)
        && nonblank(dispatch.xid)
        && !Objects.equals(receipt.xid, dispatch.xid)) conflicts.add("LOCAL_XID_CONFLICT");
    if (fence != null
        && (receipt == null
            || !Objects.equals(fence.xid, receipt.xid)
            || !Objects.equals(fence.branchId, receipt.branchId)
            || !PayTccReceiptLocalResource.ACTION_NAME.equals(fence.actionName)
            || !fenceMatches(receipt.state, fence.status))) conflicts.add("FENCE_CONFLICT");
    if (execution != null
        && "COMMITTED".equals(execution.state)
        && (receipt != null && !"CONFIRMED".equals(receipt.state)
            || dispatch != null && !"CONFIRMED".equals(dispatch.state)))
      conflicts.add("LOCAL_TERMINAL_CONFLICT");
    if (execution != null
        && "ROLLEDBACK".equals(execution.state)
        && (receipt != null && !"CANCELLED".equals(receipt.state)
            || dispatch != null && !"CANCELLED".equals(dispatch.state)))
      conflicts.add("LOCAL_TERMINAL_CONFLICT");
    if (execution == null) missing.add("EXECUTION_ROW_MISSING");
    if (dispatch == null) missing.add("DISPATCH_ROW_MISSING");
    if (receipt == null) missing.add("RECEIPT_ROW_MISSING");
    if (receipt != null && !positive(receipt.branchId)) missing.add("PAY_BRANCH_MISSING");
    if (dispatch != null && !positive(dispatch.userBranchId)) missing.add("USER_BRANCH_MISSING");
    if (receipt != null && fence == null) missing.add("FENCE_ROW_MISSING");
    boolean beginUnknown =
        execution != null && (!nonblank(execution.xid) || "BEGIN_UNKNOWN".equals(execution.state))
            || dispatch != null && !nonblank(dispatch.xid);
    String classification;
    List<String> reasons;
    if (!conflicts.isEmpty()) {
      classification = "LOCAL_CONFLICT";
      reasons = conflicts;
    } else if (tcc && beginUnknown) {
      classification = "BLOCKED_BEGIN_UNKNOWN";
      reasons = List.of("BEGIN_XID_MISSING");
    } else if (!tcc) {
      classification = "LEGACY_NOT_INSPECTED";
      reasons = List.of("LEGACY_PROTOCOL");
    } else if (execution == null && (dispatch != null || receipt != null)) {
      classification = "EXECUTION_MISSING";
      reasons = missing;
    } else if (!missing.isEmpty()) {
      classification = "BLOCKED_LOCAL_EVIDENCE";
      reasons = missing;
    } else {
      classification = "BLOCKED_EXTERNAL_EVIDENCE";
      reasons = List.of("EXTERNAL_EVIDENCE_NOT_QUERIED");
    }
    Map<String, String> identifiers = new LinkedHashMap<>();
    identifiers.put("xidSha256", hash(execution == null ? null : execution.xid));
    identifiers.put(
        "branchIdSha256",
        hash(receipt == null || receipt.branchId == null ? null : receipt.branchId.toString()));
    identifiers.put("operationKeySha256", hash(execution == null ? null : execution.operationKey));
    identifiers.put("ledgerKeySha256", hash(execution == null ? null : execution.ledgerKey));
    PayTccReconciliationSnapshotVO snapshot =
        new PayTccReconciliationSnapshotVO(
            payNo,
            safe(payment.debitMode, Set.of("LEGACY", "TCC")),
            safe(payment.status, PAYMENT_STATES),
            payment.orderNo,
            payment.userId,
            payment.amount == null ? null : payment.amount.toPlainString(),
            fact(execution != null, execution == null ? null : execution.state, EXECUTION_STATES),
            fact(dispatch != null, dispatch == null ? null : dispatch.state, DISPATCH_STATES),
            fact(receipt != null, receipt == null ? null : receipt.state, RECEIPT_STATES),
            fact(
                fence != null,
                fence == null ? null : String.valueOf(fence.status),
                Set.of("1", "2", "3", "4")),
            fact(legacy != null, legacy == null ? null : legacy.status, LEGACY_STATES),
            Map.of(
                "execution",
                execution != null
                    && (nonblank(execution.lastError) || nonblank(execution.recoveryLastError)),
                "dispatch",
                dispatch != null && nonblank(dispatch.lastError),
                "receipt",
                false));
    PayTccEvidenceAnchorDTO anchor = null;
    if ("BLOCKED_EXTERNAL_EVIDENCE".equals(classification)
        && execution != null
        && dispatch != null
        && receipt != null
        && fence != null
        && nonblank(execution.xid)
        && !Set.of("UNKNOWN", "BEGIN_UNKNOWN").contains(execution.state)
        && positive(receipt.branchId)
        && positive(dispatch.userBranchId)
        && !Objects.equals(receipt.branchId, dispatch.userBranchId)) {
      anchor =
          new PayTccEvidenceAnchorDTO(
              payNo,
              payment.userId,
              payment.orderNo,
              payment.amount.toPlainString(),
              execution.xid,
              execution.operationKey,
              execution.ledgerKey,
              receipt.branchId,
              dispatch.userBranchId,
              execution.state,
              execution.decision);
    }
    return new PayTccEvidenceCaptureDTO(
        new PayTccReconciliationVO(classification, reasons, snapshot, identifiers), anchor);
  }

  /** 严格比较本地不可变载荷，不trim、不改变金额精度或补造操作键。 */
  private static void tuple(
      PayRecordPO payment,
      String payNo,
      Long userId,
      String orderNo,
      BigDecimal amount,
      String operationKey,
      String ledgerKey,
      String source,
      List<String> conflicts) {
    if (!Objects.equals(payment.payNo, payNo)
        || !Objects.equals(payment.userId, userId)
        || !Objects.equals(payment.orderNo, orderNo)
        || !sameAmount(payment.amount, amount)
        || !Objects.equals("tcc-debit:" + payment.payNo, operationKey)
        || !Objects.equals(payment.payNo, ledgerKey)) conflicts.add(source + "_TUPLE_CONFLICT");
  }

  /** 只接受既有分支状态对应的官方Fence状态。 */
  private static boolean fenceMatches(String state, Integer status) {
    return "TRYING".equals(state) && Integer.valueOf(1).equals(status)
        || "CONFIRMED".equals(state) && Integer.valueOf(2).equals(status)
        || "CANCELLED".equals(state)
            && (Integer.valueOf(3).equals(status) || Integer.valueOf(4).equals(status));
  }

  /** 金额缺失不构成匹配。 */
  private static boolean sameAmount(BigDecimal expected, BigDecimal actual) {
    return expected != null && actual != null && expected.compareTo(actual) == 0;
  }

  /** 标识缺失或空白不补造。 */
  private static boolean nonblank(String value) {
    return value != null && !value.isBlank();
  }

  /** 原分支或用户主键必须为正。 */
  private static boolean positive(Long value) {
    return value != null && value > 0;
  }

  /** 未知状态只展示固定标识，不回显原数据库文本。 */
  private static String safe(String state, Set<String> allowed) {
    return state != null && allowed.contains(state) ? state : "UNRECOGNIZED";
  }

  /** 缺行与不认识的状态明确区分。 */
  private static PayTccLocalFactVO fact(boolean present, String state, Set<String> allowed) {
    return new PayTccLocalFactVO(present, present ? safe(state, allowed) : null);
  }

  /** SHA256只用于取证指纹，不提供可重放的原始标识。 */
  private static String hash(String value) {
    if (value == null) return null;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
