package com.cloudmall.user.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.BalanceTccDebitPO;
import com.cloudmall.user.domain.vo.UserTccReconciliationVO;
import com.cloudmall.user.domain.vo.UserTccStateVO;
import com.cloudmall.user.domain.vo.UserTerminalFrozenOwnershipEvidenceVO;
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

/** 同RR逐笔验证两种终态冻结归属，不修改资金、Fence或当前钱包。 */
@Service
public class UserTerminalFrozenOwnershipEvidenceReadService {
  /** 以REQUIRED加入外层RR的原基线代理。 */
  private final UserTccReconciliationReadService baseReader;

  /** 终态候选纯SELECT入口。 */
  private final BalanceTccDebitMapper debits;

  /** 全状态DEBIT存在性与原账本身份读取入口。 */
  private final BalanceLedgerMapper ledgers;

  /** 不注册新Bean/RM的原状态plain校验器。 */
  private final UserTccStateQuery peerQuery;

  /** 单笔标准DECIMAL金额上限，不计算历史资金总和。 */
  private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999999.99");

  /** 创建只读代理依赖和原peer校验器，不取得连接或绑定XID。 */
  public UserTerminalFrozenOwnershipEvidenceReadService(
      UserTccReconciliationReadService baseReader,
      UserTccReceiveMapper receives,
      BalanceDebitAttemptMapper attempts,
      BalanceTccDebitMapper debits,
      BalanceLedgerMapper ledgers,
      TccFenceLogMapper fences) {
    this.baseReader = baseReader;
    this.debits = debits;
    this.ledgers = ledgers;
    this.peerQuery = new UserTccStateQuery(receives, attempts, debits, ledgers, fences);
  }

  /** 原基线、候选与每笔原来源共用一个实际只读RR连接，最高仅外部缺证。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public UserTerminalFrozenOwnershipEvidenceVO inspect(Long userId, String payNo) {
    // 1. 保留基线冲突，超限不继续peer查询、不输出部分向量。
    var base = baseReader.inspect(userId, payNo);
    if ("LOCAL_CONFLICT".equals(base.classification))
      return report(base, false, null, "LOCAL_CONFLICT", base.reasonCodes);
    List<BalanceTccDebitPO> candidates = debits.findTerminalOwnershipCandidates(userId);
    if (candidates == null)
      return blocked(base, null, "BLOCKED_LOCAL_EVIDENCE", "TERMINAL_CANDIDATES_UNAVAILABLE");
    if (candidates.size() > 100)
      return blocked(
          base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "TERMINAL_CANDIDATE_LIMIT_EXCEEDED");
    // 2. 逐行证明原归属和快照，原Query证明后再核同pay全状态DEBIT。
    Set<String> paymentNumbers = new HashSet<>();
    Set<String> operationKeys = new HashSet<>();
    List<String> vector = new ArrayList<>();
    int confirmedCount = 0;
    int cancelledCount = 0;
    for (BalanceTccDebitPO candidate : candidates) {
      if (candidate == null)
        return blocked(
            base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "TERMINAL_CANDIDATE_ROW_MISSING");
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
          || !("CONFIRMED".equals(candidate.state) || "CANCELLED".equals(candidate.state))
          || !paymentNumbers.add(candidate.payNo)
          || !operationKeys.add(candidate.operationKey))
        return blocked(
            base, candidates.size(), "LOCAL_CONFLICT", "TERMINAL_CANDIDATE_IDENTITY_CONFLICT");
      if (candidate.amount == null
          || candidate.balanceBefore == null
          || candidate.balanceAfterTry == null)
        return blocked(
            base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "TERMINAL_CANDIDATE_MONEY_MISSING");
      if (!validMoney(candidate.amount)
          || candidate.amount.signum() <= 0
          || !validMoney(candidate.balanceBefore)
          || !validMoney(candidate.balanceAfterTry)
          || candidate.balanceBefore.subtract(candidate.balanceAfterTry).compareTo(candidate.amount)
              != 0)
        return blocked(
            base, candidates.size(), "LOCAL_CONFLICT", "TERMINAL_CANDIDATE_MONEY_CONFLICT");
      UserTccStateVO peer;
      try {
        peer = peerQuery.read(userId, candidate.operationKey, candidate.xid);
      } catch (DataAccessException unavailable) {
        throw unavailable;
      } catch (BizException rejected) {
        if (rejected.getHttpStatus() == 409)
          return blocked(
              base, candidates.size(), "LOCAL_CONFLICT", "TERMINAL_PEER_PERSISTED_CONFLICT");
        return blocked(
            base,
            candidates.size(),
            "BLOCKED_LOCAL_EVIDENCE",
            rejected.getHttpStatus() == 404
                ? "TERMINAL_PEER_RECEIVE_MISSING"
                : "TERMINAL_PEER_EVIDENCE_UNAVAILABLE");
      } catch (RuntimeException unproven) {
        return blocked(
            base,
            candidates.size(),
            "BLOCKED_LOCAL_EVIDENCE",
            "TERMINAL_PEER_EVIDENCE_UNAVAILABLE");
      }
      BigDecimal peerAmount = peer == null ? null : money(peer.amount);
      if (peerAmount == null)
        return blocked(
            base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "TERMINAL_PEER_MONEY_NOT_PROVEN");
      boolean confirmed = "CONFIRMED".equals(candidate.state);
      if (!Objects.equals(peer.userId, userId)
          || !Objects.equals(peer.payNo, candidate.payNo)
          || !Objects.equals(peer.orderNo, candidate.orderNo)
          || !Objects.equals(peer.operationKey, candidate.operationKey)
          || !Objects.equals(peer.ledgerKey, candidate.payNo)
          || !Objects.equals(peer.xid, candidate.xid)
          || !Objects.equals(peer.branchId, candidate.branchId)
          || !Objects.equals(peer.state, candidate.state)
          || peer.ledgerApplied != confirmed
          || peerAmount.compareTo(candidate.amount) != 0)
        return blocked(base, candidates.size(), "LOCAL_CONFLICT", "TERMINAL_PEER_PAYLOAD_CONFLICT");
      BalanceLedgerPO debitLedger = ledgers.selectDebitByPayNo(candidate.payNo);
      if (!confirmed && debitLedger != null)
        return blocked(base, candidates.size(), "LOCAL_CONFLICT", "TERMINAL_DEBIT_PROOF_CONFLICT");
      if (confirmed) {
        if (debitLedger == null
            || debitLedger.amount == null
            || debitLedger.balanceBefore == null
            || debitLedger.balanceAfter == null)
          return blocked(
              base, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", "TERMINAL_DEBIT_PROOF_MISSING");
        if (!matchesDebit(debitLedger, candidate))
          return blocked(
              base, candidates.size(), "LOCAL_CONFLICT", "TERMINAL_DEBIT_PROOF_CONFLICT");
        confirmedCount++;
      } else cancelledCount++;
      vector.add(
          hash(candidate.payNo)
              + ":"
              + hash(candidate.xid)
              + ":"
              + hash(candidate.branchId.toString())
              + ":"
              + hash(candidate.operationKey)
              + ":"
              + candidate.state
              + ":"
              + candidate.amount.toPlainString()
              + ":"
              + candidate.balanceBefore.toPlainString()
              + ":"
              + candidate.balanceAfterTry.toPlainString()
              + ";");
    }
    // 3. 仅完整候选与原基线共同通过才输出计数/摘要，不输出历史金额合计。
    if (!"BLOCKED_EXTERNAL_EVIDENCE".equals(base.classification))
      return report(base, true, candidates.size(), "BLOCKED_LOCAL_EVIDENCE", base.reasonCodes);
    vector.sort(String::compareTo);
    return new UserTerminalFrozenOwnershipEvidenceVO(
        "BLOCKED_EXTERNAL_EVIDENCE",
        List.of("EXTERNAL_EVIDENCE_NOT_QUERIED"),
        base,
        true,
        candidates.size(),
        true,
        confirmedCount,
        cancelledCount,
        hash(String.join("", vector)));
  }

  /** 已核候选的确认账本必须精确归属并复制原冻结快照，不接受同pay其它key。 */
  private static boolean matchesDebit(BalanceLedgerPO ledger, BalanceTccDebitPO candidate) {
    return Objects.equals(ledger.userId, candidate.userId)
        && Objects.equals(ledger.payNo, candidate.payNo)
        && Objects.equals(ledger.orderNo, candidate.orderNo)
        && Objects.equals(ledger.operationKey, candidate.payNo)
        && "DEBIT".equals(ledger.operationType)
        && "APPLIED".equals(ledger.state)
        && validMoney(ledger.amount)
        && validMoney(ledger.balanceBefore)
        && validMoney(ledger.balanceAfter)
        && ledger.amount.compareTo(candidate.amount) == 0
        && ledger.balanceBefore.compareTo(candidate.balanceBefore) == 0
        && ledger.balanceAfter.compareTo(candidate.balanceAfterTry) == 0;
  }

  /** 坏行/缺证/超限都不输出部分计数或未完成向量。 */
  private static UserTerminalFrozenOwnershipEvidenceVO blocked(
      UserTccReconciliationVO base, Integer count, String classification, String reason) {
    return report(base, true, count, classification, List.of(reason));
  }

  /** 保留原基线和固定原因，未核验资金摘要显式null。 */
  private static UserTerminalFrozenOwnershipEvidenceVO report(
      UserTccReconciliationVO base,
      boolean attempted,
      Integer count,
      String classification,
      List<String> reasons) {
    return new UserTerminalFrozenOwnershipEvidenceVO(
        classification, reasons, base, attempted, count, false, null, null, null);
  }

  /** 非负标准金额，存在性由调用处先校验，不截断或补0。 */
  private static boolean validMoney(BigDecimal value) {
    return value.signum() >= 0
        && value.stripTrailingZeros().scale() <= 2
        && value.compareTo(MAX_MONEY) <= 0;
  }

  /** 原peer金额文本不作浮点转换；坏格式或缺失固定缺证。 */
  private static BigDecimal money(String value) {
    if (value == null || value.length() > 20) return null;
    try {
      BigDecimal amount = new BigDecimal(value);
      return validMoney(amount) && amount.signum() > 0 ? amount : null;
    } catch (NumberFormatException malformed) {
      return null;
    }
  }

  /** 原身份只在内存形成不可逆向量，不输出其它支付号/键/XID。 */
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
