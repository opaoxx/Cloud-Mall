package com.cloudmall.user.service.impl.evidence;

import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.vo.UserLinkedRefundEvidenceVO;
import com.cloudmall.user.domain.vo.UserTccReconciliationVO;
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

/** 同一个只读RR中观察原User证据和有界退款候选，不写入或猜测补退。 */
@Service
public class UserLinkedRefundEvidenceReadService {
  /** 原User只读代理，以REQUIRED加入本服务已建立的RR。 */
  private final UserTccReconciliationReadService baseReader;

  /** 原账本关联候选和操作键CI唯一性纯SELECT入口。 */
  private final BalanceLedgerMapper ledgers;

  /** 标准DECIMAL(18,2)金额上界。 */
  private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999999.99");

  /** 创建只读服务，不持有写入Service或RM。 */
  public UserLinkedRefundEvidenceReadService(
      UserTccReconciliationReadService baseReader, BalanceLedgerMapper ledgers) {
    this.baseReader = baseReader;
    this.ledgers = ledgers;
  }

  /** 原User证据和关联候选/操作键计数共享一个实际只读RR连接。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public UserLinkedRefundEvidenceVO inspect(Long userId, String payNo) {
    // 1. 原源冲突保留，不用扩展候选补造源资金证据。
    var base = baseReader.inspect(userId, payNo);
    if ("LOCAL_CONFLICT".equals(base.classification))
      return new UserLinkedRefundEvidenceVO(
          "LOCAL_CONFLICT", base.reasonCodes, base, false, null, false, null, null);
    BigDecimal originalAmount = originalMoney(base.amount);
    if (originalAmount == null)
      return new UserLinkedRefundEvidenceVO(
          "BLOCKED_LOCAL_EVIDENCE",
          List.of("REFUND_ORIGINAL_AMOUNT_NOT_PROVEN"),
          base,
          false,
          null,
          false,
          null,
          null);
    List<BalanceLedgerPO> candidates = ledgers.findLinkedRefundCandidates(payNo);
    if (candidates == null)
      return blocked(base, null, "BLOCKED_LOCAL_EVIDENCE", "REFUND_CANDIDATES_UNAVAILABLE");
    if (candidates.size() > 100)
      return blocked(
          base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "REFUND_CANDIDATE_LIMIT_EXCEEDED");
    // 2. 验原行，不预过滤坏类型/金额，不以hash去重代替全局CI唯一性。
    BigDecimal total = new BigDecimal("0.00");
    Set<String> operationKeys = new HashSet<>();
    List<String> vector = new ArrayList<>();
    for (BalanceLedgerPO candidate : candidates) {
      if (candidate == null)
        return blocked(
            base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "REFUND_CANDIDATE_ROW_MISSING");
      if (!Objects.equals(candidate.userId, base.userId)
          || !Objects.equals(candidate.payNo, base.payNo)
          || !Objects.equals(candidate.orderNo, base.orderNo)
          || !Objects.equals(candidate.originalOperationKey, base.payNo)
          || candidate.operationKey == null
          || candidate.operationKey.isBlank()
          || candidate.operationKey.length() > 128
          || Objects.equals(candidate.operationKey, base.payNo)
          || !"REFUND".equals(candidate.operationType)
          || !"APPLIED".equals(candidate.state)
          || !operationKeys.add(candidate.operationKey))
        return blocked(
            base, candidates.size(), "LOCAL_CONFLICT", "LINKED_REFUND_IDENTITY_OR_STATE_CONFLICT");
      if (candidate.amount == null
          || candidate.balanceBefore == null
          || candidate.balanceAfter == null)
        return blocked(
            base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "LINKED_REFUND_MONEY_MISSING");
      if (!validMoney(candidate.amount)
          || candidate.amount.signum() <= 0
          || !validMoney(candidate.balanceBefore)
          || !validMoney(candidate.balanceAfter)
          || candidate.balanceAfter.subtract(candidate.balanceBefore).compareTo(candidate.amount)
              != 0)
        return blocked(base, candidates.size(), "LOCAL_CONFLICT", "LINKED_REFUND_MONEY_CONFLICT");
      Long keyCount = ledgers.countOperationCandidates(candidate.operationKey);
      if (keyCount == null || keyCount <= 0)
        return blocked(
            base,
            candidates.size(),
            "BLOCKED_LOCAL_EVIDENCE",
            "REFUND_KEY_CARDINALITY_UNAVAILABLE");
      if (keyCount != 1)
        return blocked(
            base, candidates.size(), "LOCAL_CONFLICT", "REFUND_KEY_CARDINALITY_CONFLICT");
      total = total.add(candidate.amount);
      if (total.compareTo(originalAmount) > 0)
        return blocked(base, candidates.size(), "LOCAL_CONFLICT", "REFUND_TOTAL_EXCEEDS_ORIGINAL");
      vector.add(
          hash(candidate.operationKey)
              + ":"
              + candidate.amount.toPlainString()
              + ":"
              + candidate.balanceBefore.toPlainString()
              + ":"
              + candidate.balanceAfter.toPlainString()
              + ";");
    }
    // 3. 只在原扣款与全候选证明充分时给观察摘要，保留其他源缺证。
    if (!candidates.isEmpty() && Set.of("TRYING", "CANCELLED").contains(base.tccState))
      return blocked(base, candidates.size(), "LOCAL_CONFLICT", "REFUND_ORIGINAL_STATE_CONFLICT");
    if (!candidates.isEmpty() && (!"CONFIRMED".equals(base.tccState) || !base.ledgerApplied))
      return blocked(
          base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "REFUND_ORIGINAL_DEBIT_NOT_PROVEN");
    boolean partial = !candidates.isEmpty() && total.compareTo(originalAmount) < 0;
    List<String> sourceGaps =
        base.reasonCodes.stream()
            .filter(reason -> !"EXTERNAL_EVIDENCE_NOT_QUERIED".equals(reason))
            .toList();
    boolean onlyFixedPartial =
        "BLOCKED_LOCAL_EVIDENCE".equals(base.classification)
            && !sourceGaps.isEmpty()
            && sourceGaps.stream().allMatch("REFUND_PARTIAL_OBSERVED"::equals);
    boolean fixedPartialResolved =
        onlyFixedPartial && !candidates.isEmpty() && total.compareTo(originalAmount) == 0;
    if (!"BLOCKED_EXTERNAL_EVIDENCE".equals(base.classification)
        && !fixedPartialResolved
        && !(onlyFixedPartial && partial)) {
      return new UserLinkedRefundEvidenceVO(
          "BLOCKED_LOCAL_EVIDENCE",
          sourceGaps.isEmpty() ? List.of("BASE_EVIDENCE_INCOMPLETE") : sourceGaps,
          base,
          true,
          candidates.size(),
          false,
          null,
          null);
    }
    vector.sort(String::compareTo);
    List<String> reasons =
        partial
            ? List.of("REFUND_TOTAL_PARTIAL", "EXTERNAL_EVIDENCE_NOT_QUERIED")
            : List.of("EXTERNAL_EVIDENCE_NOT_QUERIED");
    return new UserLinkedRefundEvidenceVO(
        partial ? "BLOCKED_LOCAL_EVIDENCE" : "BLOCKED_EXTERNAL_EVIDENCE",
        reasons,
        base,
        true,
        candidates.size(),
        true,
        total.toPlainString(),
        hash(String.join("", vector)));
  }

  /** 坏/缺证/超限不给总额或向量，避免部分读取变成结清凭据。 */
  private static UserLinkedRefundEvidenceVO blocked(
      UserTccReconciliationVO base, Integer count, String classification, String reason) {
    return new UserLinkedRefundEvidenceVO(
        classification, List.of(reason), base, true, count, false, null, null);
  }

  /** 非负无损标准金额，不截断或从double强转。 */
  private static boolean validMoney(BigDecimal value) {
    return value.signum() >= 0
        && value.stripTrailingZeros().scale() <= 2
        && value.compareTo(MAX_MONEY) <= 0;
  }

  /** 源金额缺失/坏格式不变成非预期500，未证明原金额时不查退款候选。 */
  private static BigDecimal originalMoney(String value) {
    if (value == null || value.length() > 20) return null;
    try {
      BigDecimal amount = new BigDecimal(value);
      return validMoney(amount) && amount.signum() > 0 ? amount : null;
    } catch (NumberFormatException malformed) {
      return null;
    }
  }

  /** 原操作键仅在内存形成SHA256指纹，摘要不返回原key。 */
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
