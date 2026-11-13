package com.cloudmall.pay.service.impl.execution;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.util.PayErrorMessages;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 不可转移的执行权、原 XID 和单向决议各自独立提交。 */
@Service
@DependsOn("payTccCancelTableInitializer")
public class PayTccExecutionStore {
  /** execution 持久化入口。 */
  private final PayTccExecutionMapper executions;

  /** 同支付号的互斥锁和原始载荷。 */
  private final PayRecordMapper payments;

  /** 旧桥尝试不得取得新 XID。 */
  private final PayTccDispatchMapper dispatches;

  /** 旧 Pay 分支不得取得新 XID。 */
  private final PayTccReceiptMapper receipts;

  /** 主动取消 gate，阻止取消已提交后再次创建 execution。 */
  private final PayTccCancelMapper cancels;

  /** 创建执行事实服务。 */
  public PayTccExecutionStore(
      PayTccExecutionMapper executions,
      PayRecordMapper payments,
      PayTccDispatchMapper dispatches,
      PayTccReceiptMapper receipts,
      PayTccCancelMapper cancels) {
    this.executions = executions;
    this.payments = payments;
    this.dispatches = dispatches;
    this.receipts = receipts;
    this.cancels = cancels;
  }

  /** 支付行锁保护首次插入，唯一胜者获得不可转移的一次性 begin 权。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claim(PayTccDispatchDTO request) {
    return claimWinner(request) != null;
  }

  /** 首次插入胜者取得原执行快照；已有合法重放返回NULL，不转移begin权。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccExecutionPO claimOwned(PayTccDispatchDTO request) {
    return claimWinner(request);
  }

  /** 两个认领入口复用同一事务内核，保原Pay锁、重放校验和fresh gate。 */
  private PayTccExecutionPO claimWinner(PayTccDispatchDTO request) {
    // 1. 校验无损载荷和原支付；所有查询均通过 Mapper。
    validateRequest(request);
    List<PayRecordPO> records = payments.selectForUpdate(request.payNo);
    if (records.size() != 1) throw conflict();
    PayRecordPO payment = records.get(0);
    if (!"TCC".equals(payment.debitMode)
        || !Objects.equals(payment.payNo, request.payNo)
        || !Objects.equals(payment.userId, request.userId)
        || !Objects.equals(payment.orderNo, request.orderNo)
        || payment.amount == null
        || payment.amount.compareTo(request.amount) != 0) throw conflict();
    PayTccExecutionPO existing = executions.selectForUpdate(request.payNo);
    if (existing != null) {
      validatePersistedExecution(existing, request.payNo);
      validate(existing, request);
      return null;
    }
    if (cancels.selectForUpdate(request.payNo) != null)
      throw new BizException("PAY_ORDER_SYNC_UNKNOWN", "取消已持久受理，不能开始扣款", 202);
    // 2. 旧独立桥/receipt 尝试不得被隐式迁入新全局事务。
    if (!"PENDING".equals(payment.status)
        || dispatches.selectByPayNo(request.payNo) != null
        || receipts.selectByPayNo(request.payNo) != null) throw conflict();
    PayTccExecutionPO execution = new PayTccExecutionPO();
    execution.id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    execution.payNo = request.payNo;
    execution.userId = request.userId;
    execution.orderNo = request.orderNo;
    execution.amount = request.amount;
    execution.operationKey = "tcc-debit:" + request.payNo;
    execution.ledgerKey = request.payNo;
    execution.state = "CLAIMED";
    execution.decision = "NONE";
    execution.createdAt = now();
    execution.updatedAt = execution.createdAt;
    execution.recoveryAttempts = 0;
    execution.nextRecoveryAt = execution.createdAt.plusSeconds(30);
    // 3. 独立提交先于任何 TC 网络调用；失败不调用 begin。
    if (executions.insert(execution) != 1) throw new IllegalStateException("execution claim 未成功");
    return execution;
  }

  /** 只有原首次执行线程把已确认的 XID 写入，不允许改绑。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void bindXid(String payNo, String xid) {
    // 1. 保原输入优先与execution锁入口。
    requireValidXid(xid);
    PayTccExecutionPO current = locked(payNo);
    // 2. 保原绑定和幂等语义，不额外读取或接管owner。
    bindCurrent(current, xid);
  }

  /** 仅将持有的原执行快照绑定XID，返回独立提交的bound事实供后续异常核对。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccExecutionPO bindOwnedXid(PayTccExecutionPO expected, String xid) {
    // 1. 输入有效后按Pay→execution锁定，核原anchor与来源。
    requireValidXid(xid);
    PayTccExecutionPO current = lockedSnapshot(expected);
    // 2. 沿原SQL绑定，保合法BEGIN_UNKNOWN进度及同XID幂等。
    bindCurrent(current, xid);
    // 3. 锁内读回原bound行；缺失不能被当作丢弃执行权的成功。
    PayTccExecutionPO bound = executions.selectByPayNo(current.payNo);
    if (bound == null) throw conflict();
    return bound;
  }

  /** 两个绑定入口共享已绑定XID的原字符域。 */
  private void requireValidXid(String xid) {
    if (xid == null || xid.isBlank() || xid.length() > 128) throw conflict();
  }

  /** 原绑定内核只对锁定行执行一次CAS，原相同XID保持幂等。 */
  private void bindCurrent(PayTccExecutionPO current, String xid) {
    if (current.xid != null) {
      if (!current.xid.equals(xid)) throw conflict();
      return;
    }
    if (!"NONE".equals(current.decision) || terminal(current.state)) throw conflict();
    if (executions.bindXid(current.payNo, xid, now()) != 1) throw conflict();
  }

  /** 决议必须在 TC 请求前独立提交，相反方向永远冲突。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccExecutionPO decide(String payNo, String xid, String decision) {
    // 1. 先核锁定行，再校验本次决议参数，保留原持久域优先级。
    PayTccExecutionPO current = locked(payNo);
    // 2. 保原决议和读回行为，不改变旧调用方的锁及来源契约。
    return decideCurrent(current, xid, decision);
  }

  /** 原owner及Pay来源仍匹配时才提交决议；身份核对先于终态和同向幂等。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccExecutionPO decideOwned(PayTccExecutionPO expected, String xid, String decision) {
    // 1. 按Pay→execution锁序核对原bound快照，不从最新行接管owner。
    PayTccExecutionPO current = lockedSnapshot(expected);
    // 2. 复用原输入、方向、终态及CAS规则。
    PayTccExecutionPO chosen = decideCurrent(current, xid, decision);
    // 3. 缺失锁内读回不作为决议成功，冲突回滚本地写入。
    if (chosen == null) throw conflict();
    return chosen;
  }

  /** 锁定行的原决议内核；同向/终态不写，相反方向冲突，更新必须恰好一行。 */
  private PayTccExecutionPO decideCurrent(PayTccExecutionPO current, String xid, String decision) {
    if (xid == null
        || xid.isBlank()
        || xid.length() > 128
        || decision == null
        || !Set.of("COMMIT", "ROLLBACK").contains(decision)
        || !Objects.equals(current.xid, xid)) throw conflict();
    // 2. 方向不可逆，原终态或同向重复决议保持幂等。
    if (!"NONE".equals(current.decision) && !decision.equals(current.decision)) throw conflict();
    if (terminal(current.state) || decision.equals(current.decision)) return current;
    // 3. 沿原CAS独立持久化决议并读回，不猜测TC结果。
    if (executions.setDecision(current.payNo, xid, decision, decision + "_REQUESTED", now()) != 1)
      throw conflict();
    return executions.selectByPayNo(current.payNo);
  }

  /** 观察结果锁内合并，写入须恰好一行；迟到未知/相反终态不能覆盖既有终态。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccExecutionPO observe(String payNo, String state, String error) {
    // 1. 保留按支付号记录诊断的原锁定入口。
    PayTccExecutionPO current = locked(payNo);
    // 2. 复用原合并规则，不在此入口推断先前读取快照。
    return mergeObservation(current, state, error);
  }

  /** 锁内核对原Pay来源和执行身份，读后漂移先于终态早返回及更新被拒绝。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccExecutionPO observeSnapshot(PayTccExecutionPO expected, String state, String error) {
    // 1. 与owned绑定复用同一原快照及来源门。
    PayTccExecutionPO current = lockedSnapshot(expected);
    // 2. 沿锁定行最新进度合并，不改变原观察规则。
    return mergeObservation(current, state, error);
  }

  /** 已决议观察先核原方向，再合并进度；旧未决议观察入口保持兼容。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccExecutionPO observeDecidedSnapshot(
      PayTccExecutionPO expected, String state, String error) {
    // 1. 沿原Pay→execution锁核身份、来源及两端持久域。
    PayTccExecutionPO current = lockedSnapshot(expected);
    // 2. 已决议方向不得变成反向或NONE，拒绝先于终态和观察参数处理。
    if ("NONE".equals(expected.decision) || !Objects.equals(current.decision, expected.decision))
      throw conflict();
    // 3. 同方向沿原合并规则写入，保终态幂等、诊断截断与影响行检查。
    return mergeObservation(current, state, error);
  }

  /** TC发送前只读复核原身份、来源与方向，返回当前进度；事务外才允许发RPC。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccExecutionPO verifyDecisionSnapshot(PayTccExecutionPO expected) {
    // 1. 沿原Pay→execution锁与snapshot门拒绝身份或来源变化。
    PayTccExecutionPO current = lockedSnapshot(expected);
    // 2. 原决议方向必须保持，不用旧快照授权另一方向或已清空的决议。
    if (!Objects.equals(current.decision, expected.decision)) throw conflict();
    // 3. 只返回最新进度，不写状态，不在本地锁内发送TC请求。
    return current;
  }

  /** 锁内复核持有的原anchor与原Pay，不从当前行重新授予一次性执行权。 */
  private PayTccExecutionPO lockedSnapshot(PayTccExecutionPO expected) {
    // 1. 拒绝缺失快照，按Pay→execution锁序取当前事实，避免与claim反向取锁。
    if (expected == null) throw conflict();
    List<PayRecordPO> sourcePayments = payments.selectForUpdate(expected.payNo);
    PayTccExecutionPO current = locked(expected.payNo);
    // 2. 核对原快照有效域和不可变身份；金额数值等价，可变进度不作版本条件。
    validatePersistedExecution(expected, expected.payNo);
    if (!Objects.equals(current.id, expected.id)
        || !Objects.equals(current.payNo, expected.payNo)
        || !Objects.equals(current.xid, expected.xid)
        || !Objects.equals(current.userId, expected.userId)
        || !Objects.equals(current.orderNo, expected.orderNo)
        || current.amount.compareTo(expected.amount) != 0
        || !Objects.equals(current.operationKey, expected.operationKey)
        || !Objects.equals(current.ledgerKey, expected.ledgerKey)) throw conflict();
    // 3. 核锁定原Pay来源；两锁随调用方独立事务一起释放。
    validatePaymentSource(current, expected.payNo, sourcePayments);
    return current;
  }

  /** 两个观察入口共用原锁内合并，不覆盖既有终态或改变当前决议方向。 */
  private PayTccExecutionPO mergeObservation(
      PayTccExecutionPO current, String state, String error) {
    // 1. 保留观察入参和未绑定XID的终态限制。
    if (state == null) throw conflict();
    if (current.xid == null && terminal(state)) throw conflict();
    // 2. 保留原终态和非终态合并规则，不给非NULL观察增加白名单。
    if (terminal(current.state)) {
      if (terminal(state) && !current.state.equals(state)) throw conflict();
      return current;
    }
    String target = state;
    if (!terminal(state))
      target =
          current.xid == null
              ? "BEGIN_UNKNOWN"
              : "NONE".equals(current.decision) ? "UNKNOWN" : current.decision + "_REQUESTED";
    // 3. 沿原诊断截断和影响行保护写入，再读回最新事实。
    error = PayErrorMessages.truncate(error);
    if (executions.updateObservation(current.payNo, target, error, now()) != 1) throw conflict();
    return executions.selectByPayNo(current.payNo);
  }

  /** 读取原执行事实与匹配的TCC支付来源；NULL XID保留prebegin，坏行不进入恢复。 */
  public PayTccExecutionPO read(String payNo) {
    // 1. 读取原支付号执行事实，缺失保留原404。
    PayTccExecutionPO execution = executions.selectByPayNo(payNo);
    if (execution == null)
      throw new BizException("PAY_TCC_EXECUTION_NOT_FOUND", "TCC execution 不存在", 404);
    // 2. 与锁内写入口共用同一有效域，保持原校验顺序。
    validatePersistedExecution(execution, payNo);
    // 3. 只读核对唯一原Pay及不可变载荷，不以当前支付进度限制恢复。
    validatePaymentSource(execution, payNo);
    return execution;
  }

  /** 校验此读取窗口的原Pay来源，不加锁、不推断资金结果或读后的事实稳定性。 */
  private void validatePaymentSource(PayTccExecutionPO execution, String payNo) {
    validatePaymentSource(execution, payNo, payments.selectByPayNo(payNo));
  }

  /** 读取与观察共用原Pay来源规则；调用方分别提供无锁或锁内事实，不限制支付进度。 */
  private void validatePaymentSource(
      PayTccExecutionPO execution, String payNo, List<PayRecordPO> records) {
    if (records.size() != 1) throw conflict();
    PayRecordPO payment = records.get(0);
    if (payment == null
        || !Objects.equals(payment.payNo, payNo)
        || !"TCC".equals(payment.debitMode)
        || !Objects.equals(payment.userId, execution.userId)
        || !Objects.equals(payment.orderNo, execution.orderNo)
        || payment.amount == null
        || payment.amount.compareTo(execution.amount) != 0) throw conflict();
  }

  /** 返回有持久证据的两端终态或需人工核验的矛盾状态。 */
  public static boolean terminal(String state) {
    return Set.of("COMMITTED", "ROLLEDBACK", "CONFLICT").contains(state);
  }

  /** 锁定后先核原身份及完整有效域，再允许bind/decide/observe早返回或写入。 */
  private PayTccExecutionPO locked(String payNo) {
    PayTccExecutionPO row = executions.selectForUpdate(payNo);
    if (row == null) throw conflict();
    validatePersistedExecution(row, payNo);
    return row;
  }

  /** 按原字段域及未绑定XID生命周期拒绝坏值，不规范化或推断已绑定资金结果。 */
  private void validatePersistedExecution(PayTccExecutionPO execution, String payNo) {
    // 1. 查询身份和已绑定XID必须有效；NULL XID仍保留prebegin语义。
    if (execution.payNo == null || !Objects.equals(execution.payNo, payNo)) throw conflict();
    if (execution.xid != null && (execution.xid.isBlank() || execution.xid.length() > 128))
      throw conflict();
    // 2. 核原状态/决议域；未确认XID只允许认领或begin未知，且尚无决议。
    if (execution.state == null
        || !Set.of(
                "CLAIMED",
                "BEGIN_UNKNOWN",
                "RUNNING",
                "COMMIT_REQUESTED",
                "ROLLBACK_REQUESTED",
                "UNKNOWN",
                "COMMITTED",
                "ROLLEDBACK",
                "CONFLICT")
            .contains(execution.state)
        || execution.decision == null
        || !Set.of("NONE", "COMMIT", "ROLLBACK").contains(execution.decision)) throw conflict();
    if (execution.xid == null
        && (!"NONE".equals(execution.decision)
            || !Set.of("CLAIMED", "BEGIN_UNKNOWN").contains(execution.state))) throw conflict();
    // 3. 核原创建载荷和固定键，只拒绝坏值，不改写历史字段。
    if (!hasValidExecutionPayload(execution)) throw conflict();
  }

  /** 复核创建域和派生键；先限金额上界再查scale，避免极端指数溢出，不规范化原字段。 */
  private boolean hasValidExecutionPayload(PayTccExecutionPO execution) {
    return execution.payNo != null
        && execution.payNo.matches("[A-Za-z0-9_-]{1,64}")
        && execution.userId != null
        && execution.userId > 0
        && execution.orderNo != null
        && !execution.orderNo.isBlank()
        && execution.orderNo.length() <= 64
        && execution.amount != null
        && execution.amount.signum() > 0
        && execution.amount.compareTo(new BigDecimal("9999999999999999.99")) <= 0
        && execution.amount.stripTrailingZeros().scale() <= 2
        && Objects.equals(execution.operationKey, "tcc-debit:" + execution.payNo)
        && Objects.equals(execution.ledgerKey, execution.payNo);
  }

  /** 重放只核对原完整载荷，不受当前配置或支付终态变化影响。 */
  private void validate(PayTccExecutionPO row, PayTccDispatchDTO request) {
    if (!Objects.equals(row.payNo, request.payNo)
        || !Objects.equals(row.userId, request.userId)
        || !Objects.equals(row.orderNo, request.orderNo)
        || row.amount.compareTo(request.amount) != 0
        || !Objects.equals(row.operationKey, "tcc-debit:" + request.payNo)
        || !Objects.equals(row.ledgerKey, request.payNo)) throw conflict();
  }

  /** 非法键与有损金额在任何 begin 或持久化之前拒绝。 */
  private void validateRequest(PayTccDispatchDTO request) {
    if (request == null
        || request.payNo == null
        || !request.payNo.matches("[A-Za-z0-9_-]{1,64}")
        || request.userId == null
        || request.userId <= 0
        || request.orderNo == null
        || request.orderNo.isBlank()
        || request.orderNo.length() > 64
        || request.amount == null
        || request.amount.signum() <= 0
        || request.amount.compareTo(new BigDecimal("9999999999999999.99")) > 0
        || request.amount.stripTrailingZeros().scale() > 2) throw conflict();
  }

  /** 使用固定数据库业务时区，避免系统时区影响审计顺序。 */
  private static LocalDateTime now() {
    return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
  }

  /** 不可变身份或决议冲突不能通过重建 execution 修复。 */
  private BizException conflict() {
    return new BizException("PAY_TCC_EXECUTION_CONFLICT", "TCC execution 原载荷或决议冲突", 409);
  }
}
