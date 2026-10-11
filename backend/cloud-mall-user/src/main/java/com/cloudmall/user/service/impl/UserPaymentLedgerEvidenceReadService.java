package com.cloudmall.user.service.impl;

import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.vo.UserLinkedRefundEvidenceVO;
import com.cloudmall.user.domain.vo.UserPaymentLedgerEvidenceVO;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 同RR核原关联报告与同pay所有类型账本，不推断或修复缺失原关联。 */
@Service
public class UserPaymentLedgerEvidenceReadService {
  /** 原关联报告Spring代理，以REQUIRED加入本外层RR。 */
  private final UserLinkedRefundEvidenceReadService linkedReader;

  /** 同pay候选与原全局key计数纯SELECT入口。 */
  private final BalanceLedgerMapper ledgers;

  /** 单笔标准DECIMAL金额容量上限。 */
  private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999999.99");

  /** 创建只读依赖，不注册RM、启动事务或取得连接。 */
  public UserPaymentLedgerEvidenceReadService(
      UserLinkedRefundEvidenceReadService linkedReader, BalanceLedgerMapper ledgers) {
    this.linkedReader = linkedReader;
    this.ledgers = ledgers;
  }

  /** 两种候选与所有key计数使用同一实际只读RR，不输出资金处置许可。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public UserPaymentLedgerEvidenceVO inspect(Long userId, String payNo) {
    // 1. 只用已证明的原来源；保留原partial，其它缺证不查询新候选。
    UserLinkedRefundEvidenceVO source = linkedReader.inspect(userId, payNo);
    if ("LOCAL_CONFLICT".equals(source.classification))
      return report(source, false, null, "LOCAL_CONFLICT", source.reasonCodes);
    if (!source.candidatesComplete || !allowedSource(source))
      return report(source, false, null, "BLOCKED_LOCAL_EVIDENCE", source.reasonCodes);
    var base = source.baseEvidence;
    BigDecimal originalAmount = base == null ? null : money(base.amount);
    BigDecimal linkedTotal = money(source.validatedTotalAmount);
    if (base == null
        || !Objects.equals(base.userId, userId)
        || !Objects.equals(base.payNo, payNo)
        || base.orderNo == null
        || base.orderNo.isBlank()
        || base.orderNo.length() > 64
        || originalAmount == null
        || originalAmount.signum() <= 0
        || linkedTotal == null
        || source.observedCount == null
        || source.observedCount < 0
        || source.observedCount > 100
        || !("CONFIRMED".equals(base.tccState)
            || "TRYING".equals(base.tccState)
            || "CANCELLED".equals(base.tccState)))
      return report(
          source, false, null, "BLOCKED_LOCAL_EVIDENCE", List.of("PAYMENT_SOURCE_NOT_PROVEN"));
    boolean confirmed = "CONFIRMED".equals(base.tccState);
    if (base.ledgerApplied != confirmed)
      return report(
          source, false, null, "LOCAL_CONFLICT", List.of("PAYMENT_SOURCE_DEBIT_CONFLICT"));
    List<BalanceLedgerPO> candidates = ledgers.findPaymentLedgerCandidates(payNo);
    if (candidates == null)
      return blocked(
          source, null, "BLOCKED_LOCAL_EVIDENCE", "PAYMENT_LEDGER_CANDIDATES_UNAVAILABLE");
    if (candidates.size() > 100)
      return blocked(
          source, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "PAYMENT_LEDGER_LIMIT_EXCEEDED");
    // 2. 保留原行的类型/关联/金额事实，不用过滤或摘要去重掩盖错link。
    int debitCount = 0;
    int refundCount = 0;
    BigDecimal refundTotal = new BigDecimal("0.00");
    Set<String> operationKeys = new HashSet<>();
    List<String> vector = new ArrayList<>();
    for (BalanceLedgerPO candidate : candidates) {
      if (candidate == null)
        return blocked(
            source, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "PAYMENT_LEDGER_ROW_MISSING");
      boolean debit = "DEBIT".equals(candidate.operationType);
      if (!Objects.equals(candidate.userId, userId)
          || !Objects.equals(candidate.payNo, payNo)
          || !Objects.equals(candidate.orderNo, base.orderNo)
          || candidate.operationKey == null
          || candidate.operationKey.isBlank()
          || candidate.operationKey.length() > 128
          || !(debit || "REFUND".equals(candidate.operationType))
          || !"APPLIED".equals(candidate.state)
          || !operationKeys.add(candidate.operationKey))
        return blocked(
            source,
            candidates.size(),
            "LOCAL_CONFLICT",
            "PAYMENT_LEDGER_IDENTITY_OR_STATE_CONFLICT");
      if (candidate.amount == null
          || candidate.balanceBefore == null
          || candidate.balanceAfter == null)
        return blocked(
            source, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "PAYMENT_LEDGER_MONEY_MISSING");
      if (!validMoney(candidate.amount)
          || candidate.amount.signum() <= 0
          || !validMoney(candidate.balanceBefore)
          || !validMoney(candidate.balanceAfter)
          || (debit
                      ? candidate.balanceBefore.subtract(candidate.balanceAfter)
                      : candidate.balanceAfter.subtract(candidate.balanceBefore))
                  .compareTo(candidate.amount)
              != 0)
        return blocked(
            source, candidates.size(), "LOCAL_CONFLICT", "PAYMENT_LEDGER_MONEY_CONFLICT");
      if (!confirmed)
        return blocked(
            source, candidates.size(), "LOCAL_CONFLICT", "PAYMENT_LEDGER_ORIGINAL_STATE_CONFLICT");
      if (debit) {
        if (!Objects.equals(candidate.operationKey, payNo)
            || candidate.originalOperationKey != null
            || candidate.amount.compareTo(originalAmount) != 0
            || ++debitCount > 1)
          return blocked(
              source, candidates.size(), "LOCAL_CONFLICT", "PAYMENT_LEDGER_DEBIT_CONFLICT");
      } else {
        if (Objects.equals(candidate.operationKey, payNo))
          return blocked(
              source, candidates.size(), "LOCAL_CONFLICT", "PAYMENT_LEDGER_REFUND_KEY_CONFLICT");
        if (!Objects.equals(candidate.originalOperationKey, payNo))
          return blocked(
              source,
              candidates.size(),
              "BLOCKED_LOCAL_EVIDENCE",
              "PAYMENT_REFUND_ORIGIN_UNPROVEN");
        refundCount++;
        refundTotal = refundTotal.add(candidate.amount);
        if (refundTotal.compareTo(originalAmount) > 0)
          return blocked(
              source, candidates.size(), "LOCAL_CONFLICT", "PAYMENT_LEDGER_REFUND_TOTAL_CONFLICT");
      }
      Long count = ledgers.countOperationCandidates(candidate.operationKey);
      if (count == null || count <= 0)
        return blocked(
            source,
            candidates.size(),
            "BLOCKED_LOCAL_EVIDENCE",
            "PAYMENT_LEDGER_KEY_COUNT_UNAVAILABLE");
      if (count != 1)
        return blocked(
            source, candidates.size(), "LOCAL_CONFLICT", "PAYMENT_LEDGER_KEY_COUNT_CONFLICT");
      vector.add(
          hash(candidate.operationKey)
              + ":"
              + candidate.operationType
              + ":"
              + candidate.amount.toPlainString()
              + ":"
              + candidate.balanceBefore.toPlainString()
              + ":"
              + candidate.balanceAfter.toPlainString()
              + ";");
    }
    // 3. 原DEBIT存在性与两范围退款数/合计均一致才给完整观察摘要。
    if (confirmed && debitCount != 1)
      return blocked(
          source, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "PAYMENT_LEDGER_DEBIT_MISSING");
    if (refundCount != source.observedCount || refundTotal.compareTo(linkedTotal) != 0)
      return blocked(
          source, candidates.size(), "LOCAL_CONFLICT", "PAYMENT_LEDGER_LINKED_TOTAL_CONFLICT");
    vector.sort(String::compareTo);
    return new UserPaymentLedgerEvidenceVO(
        source.classification,
        source.reasonCodes,
        source,
        true,
        candidates.size(),
        true,
        debitCount,
        refundCount,
        refundTotal.toPlainString(),
        hash(String.join("", vector)));
  }

  /** 仅原外部缺证或已核partial可继续，partial不抬高分类。 */
  private static boolean allowedSource(UserLinkedRefundEvidenceVO source) {
    if ("BLOCKED_EXTERNAL_EVIDENCE".equals(source.classification)) return true;
    if (!"BLOCKED_LOCAL_EVIDENCE".equals(source.classification)) return false;
    List<String> gaps =
        source.reasonCodes.stream()
            .filter(reason -> !"EXTERNAL_EVIDENCE_NOT_QUERIED".equals(reason))
            .toList();
    return !gaps.isEmpty() && gaps.stream().allMatch("REFUND_TOTAL_PARTIAL"::equals);
  }

  /** 坏/缺证/超限不输出部分资金摘要。 */
  private static UserPaymentLedgerEvidenceVO blocked(
      UserLinkedRefundEvidenceVO source, Integer count, String classification, String reason) {
    return report(source, true, count, classification, List.of(reason));
  }

  /** 保留原关联范围与缺口，新未核验摘要均为null。 */
  private static UserPaymentLedgerEvidenceVO report(
      UserLinkedRefundEvidenceVO source,
      boolean attempted,
      Integer count,
      String classification,
      List<String> reasons) {
    return new UserPaymentLedgerEvidenceVO(
        classification, reasons, source, attempted, count, false, null, null, null, null);
  }

  /** 非负无损标准金额；调用处先证明字段存在性。 */
  private static boolean validMoney(BigDecimal amount) {
    return amount.signum() >= 0
        && amount.stripTrailingZeros().scale() <= 2
        && amount.compareTo(MAX_MONEY) <= 0;
  }

  /** 原报告金额保留字符串语义，缺失/坏格式不补0。 */
  private static BigDecimal money(String value) {
    if (value == null || value.length() > 20) return null;
    try {
      BigDecimal amount = new BigDecimal(value);
      return validMoney(amount) ? amount : null;
    } catch (NumberFormatException malformed) {
      return null;
    }
  }

  /** 原key只在内存参与脱敏摘要，不回显原关联键。 */
  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
