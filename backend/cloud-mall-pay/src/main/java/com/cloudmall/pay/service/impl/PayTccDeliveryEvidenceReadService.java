package com.cloudmall.pay.service.impl;

import com.cloudmall.pay.domain.po.PayTccCancelPO;
import com.cloudmall.pay.domain.po.PayTccDeliveryPO;
import com.cloudmall.pay.domain.vo.PayTccDeliveryReconciliationVO;
import com.cloudmall.pay.domain.vo.PayTccReconciliationSnapshotVO;
import com.cloudmall.pay.domain.vo.PayTccTaskEvidenceVO;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 单个本地只读RR事务观察六来源及交付/取消，不进入任何恢复器。 */
@Service
public class PayTccDeliveryEvidenceReadService {
  /** 原六来源只读代理，以REQUIRED加入本事务。 */
  private final PayTccReconciliationReadService localReader;

  /** 交付台账纯SELECT入口。 */
  private final PayTccDeliveryMapper deliveries;

  /** 取消台账纯SELECT入口。 */
  private final PayTccCancelMapper cancels;

  /** 交付状态白名单，不回显任意数据库文本。 */
  private static final Set<String> DELIVERY_STATES =
      Set.of(
          "READY",
          "CANCEL_REQUESTED",
          "CANCEL_RECONCILING",
          "ORDER_SYNC_UNKNOWN",
          "REFUND_PENDING",
          "SUCCESS",
          "REFUNDED");

  /** 取消状态白名单，不表示外部已取消。 */
  private static final Set<String> CANCEL_STATES =
      Set.of("REQUESTED", "ORDER_CANCELLED", "CANCEL_UNKNOWN", "COMPLETED", "CONFLICT");

  /** 创建只读八来源服务，不持有写入Store或远端Client。 */
  public PayTccDeliveryEvidenceReadService(
      PayTccReconciliationReadService localReader,
      PayTccDeliveryMapper deliveries,
      PayTccCancelMapper cancels) {
    this.localReader = localReader;
    this.deliveries = deliveries;
    this.cancels = cancels;
  }

  /** 在同一个只读RR连接中采集与分类，缺表由无事务入口转换为固定503。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PayTccDeliveryReconciliationVO inspect(String payNo) {
    // 1. 原支付身份检查先于两个附加SELECT；原capture代理加入同一个RR事务。
    var local = localReader.capture(payNo).localReport();
    var snapshot = local.snapshot;
    PayTccDeliveryPO delivery = deliveries.selectByPayNo(payNo);
    PayTccCancelPO cancel = cancels.selectByPayNo(payNo);
    // 2. 只比较原持久事实，不补造台账、不改变模式、决议或恢复窗口。
    List<String> conflicts = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    if ("LEGACY".equals(snapshot.mode()) && (delivery != null || cancel != null))
      conflicts.add("TCC_TASK_ON_LEGACY_PAYMENT");
    boolean deliveryMatched = false;
    if (delivery != null) {
      boolean tupleMatched =
          tuple(snapshot, delivery.payNo, delivery.userId, delivery.orderNo, delivery.amount);
      boolean refundMatched =
          Objects.equals("refund:" + snapshot.payNo(), delivery.refundOperationKey);
      if (!tupleMatched || !refundMatched) conflicts.add("DELIVERY_IDENTITY_CONFLICT");
      String expectedXidHash = local.identifiers.get("xidSha256");
      boolean xidMatched =
          nonblank(delivery.xid)
              && expectedXidHash != null
              && Objects.equals(expectedXidHash, hash(delivery.xid));
      if (!nonblank(delivery.xid) || expectedXidHash == null)
        missing.add("DELIVERY_XID_EVIDENCE_MISSING");
      else if (!xidMatched) conflicts.add("DELIVERY_XID_CONFLICT");
      deliveryMatched = tupleMatched && refundMatched && xidMatched;
      String executionState = snapshot.execution().state();
      if (!snapshot.execution().present()
          || Set.of("UNKNOWN", "BEGIN_UNKNOWN", "UNRECOGNIZED").contains(executionState))
        missing.add("DELIVERY_COMMIT_EVIDENCE_MISSING");
      else if (!"COMMITTED".equals(executionState)) conflicts.add("DELIVERY_EXECUTION_CONFLICT");
      if (delivery.state == null || !DELIVERY_STATES.contains(delivery.state))
        conflicts.add("DELIVERY_STATE_UNRECOGNIZED");
      else if (!deliveryPaymentMatches(delivery.state, snapshot.paymentState()))
        conflicts.add("DELIVERY_PAYMENT_STATE_CONFLICT");
    } else if ("COMMITTED".equals(snapshot.execution().state()))
      missing.add("DELIVERY_ROW_MISSING");
    boolean cancelMatched = false;
    if (cancel != null) {
      cancelMatched = tuple(snapshot, cancel.payNo, cancel.userId, cancel.orderNo, cancel.amount);
      if (!cancelMatched) conflicts.add("CANCEL_IDENTITY_CONFLICT");
      if ("CONFLICT".equals(cancel.state)) conflicts.add("CANCEL_RECORDED_CONFLICT");
      if (snapshot.execution().present()
          || snapshot.dispatch().present()
          || snapshot.receipt().present()
          || snapshot.legacyIntent().present()
          || delivery != null) conflicts.add("CANCEL_FUNDS_EVIDENCE_CONFLICT");
      if (cancel.state == null || !CANCEL_STATES.contains(cancel.state))
        conflicts.add("CANCEL_STATE_UNRECOGNIZED");
      else if (!("COMPLETED".equals(cancel.state) ? "FAILED" : "PENDING")
          .equals(snapshot.paymentState())) conflicts.add("CANCEL_PAYMENT_STATE_CONFLICT");
    }
    // 3. 冲突优先，原BEGIN/缺证不降级；即使本地一致也不宣称资金终态。
    String classification = local.classification;
    List<String> reasons = new ArrayList<>(local.reasonCodes);
    if (!conflicts.isEmpty()) {
      classification = "LOCAL_CONFLICT";
      reasons.addAll(conflicts);
    } else if (!missing.isEmpty()) {
      if ("BLOCKED_EXTERNAL_EVIDENCE".equals(classification))
        classification = "BLOCKED_LOCAL_EVIDENCE";
      reasons.addAll(missing);
    }
    return new PayTccDeliveryReconciliationVO(
        classification,
        reasons,
        local,
        delivery == null
            ? absent()
            : new PayTccTaskEvidenceVO(
                true,
                safe(delivery.state, DELIVERY_STATES),
                deliveryMatched,
                delivery.attempts,
                time(delivery.nextRetryAt),
                nonblank(delivery.leaseOwner),
                time(delivery.leaseUntil),
                nonblank(delivery.lastError),
                hash(delivery.xid),
                hash(delivery.refundOperationKey)),
        cancel == null
            ? absent()
            : new PayTccTaskEvidenceVO(
                true,
                safe(cancel.state, CANCEL_STATES),
                cancelMatched,
                cancel.attempts,
                time(cancel.nextRetryAt),
                nonblank(cancel.leaseOwner),
                time(cancel.leaseUntil),
                nonblank(cancel.lastError),
                null,
                null));
  }

  /** 精确身份与BigDecimal数值比较，不trim、不改归属、不截断金额。 */
  private static boolean tuple(
      PayTccReconciliationSnapshotVO snapshot,
      String payNo,
      Long userId,
      String orderNo,
      BigDecimal amount) {
    return Objects.equals(snapshot.payNo(), payNo)
        && Objects.equals(snapshot.userId(), userId)
        && Objects.equals(snapshot.orderNo(), orderNo)
        && snapshot.amount() != null
        && amount != null
        && new BigDecimal(snapshot.amount()).compareTo(amount) == 0;
  }

  /** 只核既有同事务投影约束，COMMITTED加REFUNDED属于合法后续退款。 */
  private static boolean deliveryPaymentMatches(String state, String paymentState) {
    if (Set.of("READY", "CANCEL_REQUESTED", "CANCEL_RECONCILING").contains(state))
      return Set.of("PENDING", "ORDER_SYNC_UNKNOWN").contains(paymentState);
    return state.equals(paymentState);
  }

  /** 缺行保留null，不补成READY、COMPLETED或零尝试。 */
  private static PayTccTaskEvidenceVO absent() {
    return new PayTccTaskEvidenceVO(false, null, null, null, null, false, null, false, null, null);
  }

  /** 未知状态返回固定标识，不泄露数据库文本。 */
  private static String safe(String state, Set<String> allowed) {
    return state != null && allowed.contains(state) ? state : "UNRECOGNIZED";
  }

  /** 租约/错误只观察是否有值，不推断恢复权。 */
  private static boolean nonblank(String value) {
    return value != null && !value.isBlank();
  }

  /** DATETIME按项目上海语义输出，不改数据库或系统时区。 */
  private static String time(LocalDateTime value) {
    return value == null ? null : value.atOffset(ZoneOffset.ofHours(8)).toString();
  }

  /** 与原六来源相同的UTF8 SHA256指纹，原标识不输出。 */
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
