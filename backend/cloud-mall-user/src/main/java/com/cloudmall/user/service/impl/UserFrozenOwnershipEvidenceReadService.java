package com.cloudmall.user.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.po.BalanceTccDebitPO;
import com.cloudmall.user.domain.vo.UserFrozenOwnershipEvidenceVO;
import com.cloudmall.user.domain.vo.UserTccReconciliationVO;
import com.cloudmall.user.domain.vo.UserTccStateVO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
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
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 同RR核活跃冻结候选的原资金/Fence归属，不执行Try/Confirm/Cancel。 */
@Service
public class UserFrozenOwnershipEvidenceReadService {
  /** 现User基线代理，以REQUIRED加入同一个外层RR。 */
  private final UserTccReconciliationReadService baseReader;

  /** 原TRYING候选纯SELECT入口。 */
  private final BalanceTccDebitMapper debits;

  /** 原状态校验器plain delegate，所有SQL在本服务外层RR内。 */
  private final UserTccStateQuery peerQuery;

  /** 标准DECIMAL(18,2)金额上界。 */
  private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999999.99");

  /** 创建只读原校验器，不增加同类型SpringBean或RM。 */
  public UserFrozenOwnershipEvidenceReadService(
      UserTccReconciliationReadService baseReader,
      UserTccReceiveMapper receives,
      BalanceDebitAttemptMapper attempts,
      BalanceTccDebitMapper debits,
      BalanceLedgerMapper ledgers,
      TccFenceLogMapper fences) {
    this.baseReader = baseReader;
    this.debits = debits;
    this.peerQuery = new UserTccStateQuery(receives, attempts, debits, ledgers, fences);
  }

  /** 候选/peer/账户合计在一个实际只读RR连接内，无跨库或终态承诺。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public UserFrozenOwnershipEvidenceVO inspect(Long userId, String payNo) {
    // 1. 保原源冲突与金额缺证，不补造可用于peer查询的资金参数。
    var base = baseReader.inspect(userId, payNo);
    if ("LOCAL_CONFLICT".equals(base.classification))
      return new UserFrozenOwnershipEvidenceVO(
          "LOCAL_CONFLICT", base.reasonCodes, base, false, null, false, null, null);
    BigDecimal accountFrozen =
        base.account == null ? null : money(base.account.frozenBalance(), false);
    BigDecimal sourceTotal =
        base.account == null ? null : money(base.account.tryingFrozenTotal(), true);
    if (base.account == null
        || !base.account.present()
        || accountFrozen == null
        || sourceTotal == null)
      return new UserFrozenOwnershipEvidenceVO(
          "BLOCKED_LOCAL_EVIDENCE",
          List.of("FROZEN_BASE_AMOUNT_NOT_PROVEN"),
          base,
          false,
          null,
          false,
          null,
          null);
    if (accountFrozen.compareTo(sourceTotal) != 0)
      return new UserFrozenOwnershipEvidenceVO(
          "LOCAL_CONFLICT",
          List.of("FROZEN_OWNERSHIP_TOTAL_CONFLICT"),
          base,
          false,
          null,
          false,
          null,
          null);
    List<BalanceTccDebitPO> candidates = debits.findTryingOwnershipCandidates(userId);
    if (candidates == null)
      return blocked(base, null, "BLOCKED_LOCAL_EVIDENCE", "FROZEN_CANDIDATES_UNAVAILABLE");
    if (candidates.size() > 100)
      return blocked(
          base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "FROZEN_CANDIDATE_LIMIT_EXCEEDED");
    // 2. 只用原PO参数查peer，每条冻结须与原请求、TCC归属和TRYING Fence一致。
    BigDecimal total = new BigDecimal("0.00");
    Set<String> paymentNumbers = new HashSet<>();
    Set<String> operationKeys = new HashSet<>();
    List<String> vector = new ArrayList<>();
    for (BalanceTccDebitPO candidate : candidates) {
      if (candidate == null)
        return blocked(
            base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "FROZEN_CANDIDATE_ROW_MISSING");
      if (!Objects.equals(candidate.userId, userId)
          || candidate.payNo == null
          || !candidate.payNo.matches("[A-Za-z0-9_-]{1,64}")
          || !Objects.equals(candidate.operationKey, "tcc-debit:" + candidate.payNo)
          || candidate.orderNo == null
          || candidate.orderNo.isBlank()
          || candidate.orderNo.length() > 64
          || candidate.xid == null
          || candidate.xid.isBlank()
          || candidate.xid.length() > 128
          || candidate.branchId == null
          || candidate.branchId <= 0
          || !"TRYING".equals(candidate.state)
          || !paymentNumbers.add(candidate.payNo)
          || !operationKeys.add(candidate.operationKey))
        return blocked(
            base, candidates.size(), "LOCAL_CONFLICT", "FROZEN_CANDIDATE_IDENTITY_CONFLICT");
      if (candidate.amount == null
          || candidate.balanceBefore == null
          || candidate.balanceAfterTry == null)
        return blocked(
            base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "FROZEN_CANDIDATE_MONEY_MISSING");
      if (!validMoney(candidate.amount)
          || candidate.amount.signum() <= 0
          || !validMoney(candidate.balanceBefore)
          || !validMoney(candidate.balanceAfterTry)
          || candidate.balanceBefore.subtract(candidate.balanceAfterTry).compareTo(candidate.amount)
              != 0)
        return blocked(
            base, candidates.size(), "LOCAL_CONFLICT", "FROZEN_CANDIDATE_MONEY_CONFLICT");
      UserTccStateVO peer;
      try {
        peer = peerQuery.read(userId, candidate.operationKey, candidate.xid);
      } catch (DataAccessException unavailable) {
        throw unavailable;
      } catch (BizException rejected) {
        if (rejected.getHttpStatus() == 404)
          return blocked(
              base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "FROZEN_PEER_RECEIVE_MISSING");
        if (rejected.getHttpStatus() == 409)
          return blocked(
              base, candidates.size(), "LOCAL_CONFLICT", "FROZEN_PEER_PERSISTED_CONFLICT");
        return blocked(
            base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "FROZEN_PEER_EVIDENCE_UNAVAILABLE");
      } catch (RuntimeException unproven) {
        return blocked(
            base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "FROZEN_PEER_EVIDENCE_UNAVAILABLE");
      }
      if (peer == null || peer.amount == null)
        return blocked(
            base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "FROZEN_PEER_PAYLOAD_MISSING");
      BigDecimal peerAmount = money(peer.amount, false);
      if (peerAmount == null)
        return blocked(
            base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "FROZEN_PEER_MONEY_NOT_PROVEN");
      if (!Objects.equals(peer.userId, userId)
          || !Objects.equals(peer.payNo, candidate.payNo)
          || !Objects.equals(peer.orderNo, candidate.orderNo)
          || !Objects.equals(peer.operationKey, candidate.operationKey)
          || !Objects.equals(peer.ledgerKey, candidate.payNo)
          || !Objects.equals(peer.xid, candidate.xid)
          || !Objects.equals(peer.branchId, candidate.branchId)
          || !"TRYING".equals(peer.state)
          || peer.ledgerApplied
          || peerAmount.compareTo(candidate.amount) != 0)
        return blocked(base, candidates.size(), "LOCAL_CONFLICT", "FROZEN_PEER_PAYLOAD_CONFLICT");
      total = total.add(candidate.amount);
      vector.add(
          hash(candidate.payNo)
              + ":"
              + hash(candidate.xid)
              + ":"
              + hash(candidate.branchId.toString())
              + ":"
              + hash(candidate.operationKey)
              + ":"
              + candidate.amount.toPlainString()
              + ":"
              + candidate.balanceBefore.toPlainString()
              + ":"
              + candidate.balanceAfterTry.toPlainString()
              + ";");
    }
    // 3. 所有TRYING候选归属与源金额同时匹配才给摘要；原其他缺口保留。
    if (total.compareTo(accountFrozen) != 0 || total.compareTo(sourceTotal) != 0)
      return blocked(base, candidates.size(), "LOCAL_CONFLICT", "FROZEN_OWNERSHIP_TOTAL_CONFLICT");
    if (!"BLOCKED_EXTERNAL_EVIDENCE".equals(base.classification))
      return new UserFrozenOwnershipEvidenceVO(
          "BLOCKED_LOCAL_EVIDENCE",
          base.reasonCodes,
          base,
          true,
          candidates.size(),
          false,
          null,
          null);
    vector.sort(String::compareTo);
    return new UserFrozenOwnershipEvidenceVO(
        "BLOCKED_EXTERNAL_EVIDENCE",
        List.of("EXTERNAL_EVIDENCE_NOT_QUERIED"),
        base,
        true,
        candidates.size(),
        true,
        total.toPlainString(),
        hash(String.join("", vector)));
  }

  /** 未完成证明不输出其他交易的资金标识或部分合计。 */
  private static UserFrozenOwnershipEvidenceVO blocked(
      UserTccReconciliationVO base, Integer count, String classification, String reason) {
    return new UserFrozenOwnershipEvidenceVO(
        classification, List.of(reason), base, true, count, false, null, null);
  }

  /** 非负无损标准金额，字段存在性由调用处先校验。 */
  private static boolean validMoney(BigDecimal value) {
    return value.signum() >= 0
        && value.stripTrailingZeros().scale() <= 2
        && value.compareTo(MAX_MONEY) <= 0;
  }

  /** 源文本缺失/坏格式固定缺证，聚合允许SUM扩展但仍最多65 digits/2scale。 */
  private static BigDecimal money(String value, boolean sum) {
    if (value == null || value.length() > (sum ? 67 : 20)) return null;
    try {
      BigDecimal amount = new BigDecimal(value);
      return amount.signum() >= 0
              && amount.stripTrailingZeros().scale() <= 2
              && amount.precision() <= (sum ? 65 : 18)
              && (sum || amount.compareTo(MAX_MONEY) <= 0)
          ? amount
          : null;
    } catch (NumberFormatException malformed) {
      return null;
    }
  }

  /** 摘要只给SHA256，不返回其他支付号/键/XID/branch。 */
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
