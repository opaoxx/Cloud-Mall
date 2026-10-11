package com.cloudmall.user.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.user.domain.po.BalanceLedgerPO;
import com.cloudmall.user.domain.po.UserTccReceivePO;
import com.cloudmall.user.domain.vo.UserAccountEvidenceVO;
import com.cloudmall.user.domain.vo.UserRefundEvidenceVO;
import com.cloudmall.user.domain.vo.UserTccReconciliationVO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
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

/** 一个实际只读RR事务内复用原TCC校验器并补账户、冻结及固定退款键证据。 */
@Service
public class UserTccReconciliationReadService {
  /** 原接收候选，不锁定或取得接收权。 */
  private final UserTccReceiveMapper receives;

  /** 原有业务事实校验器，plain delegate的SQL全在本服务外层RR中执行。 */
  private final UserTccStateQuery stateQuery;

  /** 账户资金部分投影Mapper。 */
  private final UserMapper users;

  /** 按用户观察冻结总额的纯SELECT Mapper。 */
  private final BalanceTccDebitMapper debits;

  /** 固定退款键纯SELECT Mapper。 */
  private final BalanceLedgerMapper ledgers;

  /** 项目DECIMAL(18,2)金额上界。 */
  private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999999.99");

  /** 创建原校验器纯读取委托，不创建新Spring同类型Bean或依赖写RM。 */
  public UserTccReconciliationReadService(
      UserTccReceiveMapper receives,
      BalanceDebitAttemptMapper attempts,
      BalanceTccDebitMapper debits,
      BalanceLedgerMapper ledgers,
      TccFenceLogMapper fences,
      UserMapper users) {
    this.receives = receives;
    this.debits = debits;
    this.ledgers = ledgers;
    this.users = users;
    this.stateQuery = new UserTccStateQuery(receives, attempts, debits, ledgers, fences);
  }

  /** 原持久载荷提供查询锚点，完整样本九SELECT共享一个无锁只读RR连接。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public UserTccReconciliationVO inspect(Long userId, String payNo) {
    // 1. 保留CI候选并精确核身份，原XID/键只取自数据库，不取HTTP覆盖值。
    UserTccReceivePO receive = receives.findByPayNo(payNo);
    if (receive == null) throw new BizException("COMMON_NOT_FOUND", "未观察到原TCC接收事实", 404);
    requireReceive(receive, userId, payNo);
    var state = stateQuery.read(userId, receive.operationKey, receive.xid);
    // 2. 在同一个快照中观察账户资金字段、所有该用户冻结行与固定退款键。
    var account = users.selectBalanceEvidenceById(userId);
    Map<String, Object> aggregate = debits.selectFrozenEvidence(userId);
    BalanceLedgerPO refund = ledgers.selectByOperationKey("refund:" + payNo);
    List<String> conflicts = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    if ("UNKNOWN".equals(state.state)) missing.add("USER_TCC_FUNDS_EVIDENCE_MISSING");
    if (account == null) missing.add("ACCOUNT_ROW_MISSING");
    else {
      if (!Objects.equals(account.id, userId)) conflicts.add("ACCOUNT_IDENTITY_CONFLICT");
      if (account.status == null || !Set.of(0, 1).contains(account.status))
        conflicts.add("ACCOUNT_STATUS_UNRECOGNIZED");
      checkMoney(account.balance, "ACCOUNT_BALANCE", conflicts, missing);
      checkMoney(account.frozenBalance, "ACCOUNT_FROZEN", conflicts, missing);
    }
    BigDecimal frozenTotal = null;
    boolean aggregateValid = false;
    if (aggregate == null
        || !(aggregate.get("frozen_total") instanceof BigDecimal total)
        || !(aggregate.get("invalid_row_count") instanceof Long invalidRows)
        || invalidRows < 0) missing.add("FROZEN_AGGREGATE_EVIDENCE_MISSING");
    else if (invalidRows > 0) conflicts.add("FROZEN_SOURCE_ROWS_INVALID");
    else if (total.signum() < 0 || total.stripTrailingZeros().scale() > 2)
      missing.add("FROZEN_AGGREGATE_EVIDENCE_MISSING");
    else {
      frozenTotal = total;
      aggregateValid = true;
    }
    Boolean frozenMatched = null;
    if (aggregateValid && account != null && validMoney(account.frozenBalance)) {
      frozenMatched = frozenTotal.compareTo(account.frozenBalance) == 0;
      if (!frozenMatched) conflicts.add("FROZEN_PROJECTION_CONFLICT");
    }
    boolean refundIdentityMatched = false;
    if (refund != null) {
      refundIdentityMatched =
          Objects.equals(refund.userId, userId)
              && Objects.equals(refund.payNo, payNo)
              && Objects.equals(refund.orderNo, receive.orderNo)
              && Objects.equals(refund.operationKey, "refund:" + payNo)
              && Objects.equals(refund.originalOperationKey, payNo);
      if (!refundIdentityMatched) conflicts.add("REFUND_IDENTITY_CONFLICT");
      if (!"REFUND".equals(refund.operationType) || !"APPLIED".equals(refund.state))
        conflicts.add("REFUND_STATE_CONFLICT");
      checkMoney(refund.amount, "REFUND_AMOUNT", conflicts, missing);
      checkMoney(refund.balanceBefore, "REFUND_BALANCE_BEFORE", conflicts, missing);
      checkMoney(refund.balanceAfter, "REFUND_BALANCE_AFTER", conflicts, missing);
      if (validMoney(refund.amount)) {
        if (refund.amount.signum() == 0 || refund.amount.compareTo(receive.amount) > 0)
          conflicts.add("REFUND_AMOUNT_CONFLICT");
        else if (refund.amount.compareTo(receive.amount) < 0)
          missing.add("REFUND_PARTIAL_OBSERVED");
        if (validMoney(refund.balanceBefore)
            && validMoney(refund.balanceAfter)
            && refund.balanceAfter.subtract(refund.balanceBefore).compareTo(refund.amount) != 0)
          conflicts.add("REFUND_BALANCE_DELTA_CONFLICT");
      }
      if (Set.of("TRYING", "CANCELLED").contains(state.state) && "APPLIED".equals(refund.state))
        conflicts.add("REFUND_TCC_STATE_CONFLICT");
      else if (!"CONFIRMED".equals(state.state) || !state.ledgerApplied)
        missing.add("REFUND_ORIGINAL_DEBIT_EVIDENCE_MISSING");
    }
    // 3. 冲突优先；一致观察仍待其他系统，不猜终态或退款许可。
    String classification =
        !conflicts.isEmpty()
            ? "LOCAL_CONFLICT"
            : !missing.isEmpty() ? "BLOCKED_LOCAL_EVIDENCE" : "BLOCKED_EXTERNAL_EVIDENCE";
    List<String> reasons = new ArrayList<>(conflicts);
    reasons.addAll(missing);
    reasons.add("EXTERNAL_EVIDENCE_NOT_QUERIED");
    Map<String, String> identifiers = new LinkedHashMap<>();
    identifiers.put("xidSha256", hash(state.xid));
    identifiers.put(
        "branchIdSha256", hash(state.branchId == null ? null : state.branchId.toString()));
    identifiers.put("operationKeySha256", hash(state.operationKey));
    identifiers.put("ledgerKeySha256", hash(state.ledgerKey));
    return new UserTccReconciliationVO(
        state,
        classification,
        reasons,
        new UserAccountEvidenceVO(
            account != null,
            account == null
                ? null
                : Integer.valueOf(1).equals(account.status)
                    ? "ACTIVE"
                    : Integer.valueOf(0).equals(account.status) ? "DISABLED" : "UNRECOGNIZED",
            account == null ? null : money(account.balance),
            account == null ? null : money(account.frozenBalance),
            money(frozenTotal),
            frozenMatched),
        refund == null
            ? new UserRefundEvidenceVO(false, null, null, null, null, null, null, null)
            : new UserRefundEvidenceVO(
                true,
                "APPLIED".equals(refund.state) ? "APPLIED" : "UNRECOGNIZED",
                money(refund.amount),
                money(refund.balanceBefore),
                money(refund.balanceAfter),
                refundIdentityMatched,
                hash(refund.operationKey),
                hash(refund.originalOperationKey)),
        identifiers);
  }

  /** 接收标识严格匹配原资金协议，坏数据不成为查询锚点。 */
  private static void requireReceive(UserTccReceivePO receive, Long userId, String payNo) {
    if (!Objects.equals(receive.payNo, payNo)
        || !Objects.equals(receive.userId, userId)
        || !Objects.equals(receive.operationKey, "tcc-debit:" + payNo)
        || !Objects.equals(receive.ledgerKey, payNo)
        || !validMoney(receive.amount)
        || receive.amount.signum() <= 0
        || receive.orderNo == null
        || receive.orderNo.isBlank()
        || receive.orderNo.length() > 64
        || receive.xid == null
        || receive.xid.isBlank()
        || receive.xid.length() > 128
        || !"CLAIMED".equals(receive.state)
        || receive.branchId != null && receive.branchId <= 0)
      throw new BizException("USER_TCC_RECONCILIATION_CONFLICT", "原TCC接收载荷不一致", 409);
  }

  /** 缺字段为缺证，非法金额为冲突；不补零、强转double或截断。 */
  private static void checkMoney(
      BigDecimal value, String source, List<String> conflicts, List<String> missing) {
    if (value == null) missing.add(source + "_MISSING");
    else if (!validMoney(value)) conflicts.add(source + "_INVALID");
  }

  /** 原金额须符合非负DECIMAL(18,2)，退款正数另行核验。 */
  private static boolean validMoney(BigDecimal value) {
    return value != null
        && value.signum() >= 0
        && value.stripTrailingZeros().scale() <= 2
        && value.compareTo(MAX_MONEY) <= 0;
  }

  /** 金额原值使用无指数文本，保留null。 */
  private static String money(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }

  /** UTF8 SHA256只作不可逆证据标识，不提供可重放资金参数。 */
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
