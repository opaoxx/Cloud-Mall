package com.cloudmall.pay.service.impl;

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
      return false;
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
    return true;
  }

  /** 只有原首次执行线程把已确认的 XID 写入，不允许改绑。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void bindXid(String payNo, String xid) {
    if (xid == null || xid.isBlank() || xid.length() > 128) throw conflict();
    PayTccExecutionPO current = locked(payNo);
    if (current.xid != null) {
      if (!current.xid.equals(xid)) throw conflict();
      return;
    }
    if (!"NONE".equals(current.decision) || terminal(current.state)) throw conflict();
    if (executions.bindXid(payNo, xid, now()) != 1) throw conflict();
  }

  /** 决议必须在 TC 请求前独立提交，相反方向永远冲突。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccExecutionPO decide(String payNo, String xid, String decision) {
    // 1. 先核锁定行，再校验本次决议参数，保留原持久域优先级。
    PayTccExecutionPO current = locked(payNo);
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
    if (executions.setDecision(payNo, xid, decision, decision + "_REQUESTED", now()) != 1)
      throw conflict();
    return executions.selectByPayNo(payNo);
  }

  /** 观察结果锁内合并，写入须恰好一行；迟到未知/相反终态不能覆盖既有终态。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccExecutionPO observe(String payNo, String state, String error) {
    // 1. 先核锁定行，再拒绝缺失的观察入参。
    PayTccExecutionPO current = locked(payNo);
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
    if (executions.updateObservation(payNo, target, error, now()) != 1) throw conflict();
    return executions.selectByPayNo(payNo);
  }

  /** 读取原执行事实有效域和创建载荷；NULL XID保留prebegin，坏行不进入恢复。 */
  public PayTccExecutionPO read(String payNo) {
    // 1. 读取原支付号执行事实，缺失保留原404。
    PayTccExecutionPO execution = executions.selectByPayNo(payNo);
    if (execution == null)
      throw new BizException("PAY_TCC_EXECUTION_NOT_FOUND", "TCC execution 不存在", 404);
    // 2. 与锁内写入口共用同一有效域，保持原校验顺序。
    validatePersistedExecution(execution, payNo);
    // 3. 返回原执行事实，不改写任何字段。
    return execution;
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
