package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.PayTccExecutionVO;
import com.cloudmall.pay.service.PayTccBridgeService;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.PayTccReceiptService;
import com.cloudmall.pay.service.PayTccTransactionClient;
import io.seata.core.context.RootContext;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 一次性启动、不可变决议、只核对原资金事实的内部 coordinator。 */
@Service
@ConditionalOnProperty(name = "cloudmall.pay.tcc-coordinator.enabled", havingValue = "true")
public class PayTccCoordinatorServiceImpl implements PayTccCoordinatorService {
  /** 独立提交的执行权/XID/决议。 */
  private final PayTccExecutionStore executions;

  /** Pay 唯一 LocalTCC 认领入口。 */
  private final PayTccReceiptService receipts;

  /** User 一次性 POST 和后续 GET。 */
  private final PayTccBridgeService bridge;

  /** 本地 receipt/Fence 与 User 镜像的完整核对。 */
  private final PayTccExecutionEvidence evidence;

  /** 官方 TC 窄接口，不用 RPC 返回猜资金结果。 */
  private final PayTccTransactionClient transactions;

  /** 创建显式内部编排，不在构造过程中产生全局事务。 */
  public PayTccCoordinatorServiceImpl(
      PayTccExecutionStore executions,
      PayTccReceiptService receipts,
      PayTccBridgeService bridge,
      PayTccExecutionEvidence evidence,
      PayTccTransactionClient transactions) {
    this.executions = executions;
    this.receipts = receipts;
    this.bridge = bridge;
    this.evidence = evidence;
    this.transactions = transactions;
  }

  /** 只有首次插入胜者能 begin/两个 Try，所有重入进入只读恢复。 */
  @Override
  public PayTccExecutionVO execute(PayTccDispatchDTO request) {
    requireNoContext();
    // 1. 持久化执行权先于 begin，重放不能继承初次线程的执行权。
    PayTccExecutionPO owned = executions.claimOwned(request);
    if (owned == null) return recover(request.payNo);
    try {
      String xid = transactions.begin();
      if (xid == null || !Objects.equals(xid, RootContext.getXID()))
        throw new IllegalStateException("TC begin 未绑定原 XID");
      owned = executions.bindOwnedXid(owned, xid);
      // 2. 两分支仅初次执行；明确冲突停止，普通失败请求回滚而非声称已回滚。
      String decision = "ROLLBACK";
      try {
        if ("TRYING".equals(receipts.prepare(request).state)) {
          PayTccDispatchVO user = bridge.dispatch(request);
          if ("TRYING".equals(evidence.classify(executions.read(request.payNo), user)))
            decision = "COMMIT";
        }
      } catch (RuntimeException tryFailure) {
        rethrowConflict(tryFailure);
        executions.observeSnapshot(owned, "UNKNOWN", tryFailure.getClass().getSimpleName());
      }
      owned = executions.decideOwned(owned, xid, decision);
      // 3. 决议独立提交后才发 RPC，之后永远不能改变方向。
      sendDecision(owned);
    } catch (Exception beginOrPersistenceFailure) {
      rethrowConflict(beginOrPersistenceFailure);
      executions.observeSnapshot(
          owned, "UNKNOWN", beginOrPersistenceFailure.getClass().getSimpleName());
    } finally {
      // 4. 入口已拒绝继承上下文，因此只清理本次 begin 建立的绑定。
      RootContext.unbind();
    }
    return PayTccExecutionVO.from(observe(executions.verifyDecisionSnapshot(owned)));
  }

  /** 只读核对两端；已有决议可用原 XID 重试，NONE 永不自动决议。 */
  @Override
  public PayTccExecutionVO recover(String payNo) {
    requireNoContext();
    // 1. 先核对持久事实；两个终态齐全时无需 TC 历史状态。
    PayTccExecutionPO current = observe(payNo);
    if (PayTccExecutionStore.terminal(current.state)
        || current.xid == null
        || "NONE".equals(current.decision)) return PayTccExecutionVO.from(current);
    // 2. 同向 RPC 可重复，绝不重新 begin 或执行任何 Try。
    sendDecision(current);
    return PayTccExecutionVO.from(observe(executions.verifyDecisionSnapshot(current)));
  }

  /** 明确的业务冲突原样传播；其它异常保留原未知/回滚流程，不按HTTP状态猜测冲突。 */
  private void rethrowConflict(Exception failure) {
    if (failure instanceof BizException) {
      BizException businessFailure = (BizException) failure;
      if (businessFailure.getCode() != null && businessFailure.getCode().contains("CONFLICT"))
        throw businessFailure;
    }
  }

  /** 发送前复核原决议快照；RPC失败诊断仍关联原快照，不推断资金终态。 */
  private void sendDecision(PayTccExecutionPO execution) {
    PayTccExecutionPO current = executions.verifyDecisionSnapshot(execution);
    if (PayTccExecutionStore.terminal(current.state)) return;
    try {
      if ("COMMIT".equals(execution.decision)) transactions.commit(execution.xid);
      else if ("ROLLBACK".equals(execution.decision)) transactions.rollback(execution.xid);
    } catch (Exception decisionResponseLost) {
      executions.observeSnapshot(
          execution, "UNKNOWN", decisionResponseLost.getClass().getSimpleName());
    }
  }

  /** 读关联结果锁内核对原执行快照，网络未知不覆盖已替换的 execution。 */
  private PayTccExecutionPO observe(String payNo) {
    return observe(executions.read(payNo));
  }

  /** 原观察内核只消费调用方已核对的快照；不按支付号重新取得其它owner。 */
  private PayTccExecutionPO observe(PayTccExecutionPO execution) {
    if (PayTccExecutionStore.terminal(execution.state)) return execution;
    if (execution.xid == null)
      return executions.observeSnapshot(execution, "UNKNOWN", "begin XID 尚无持久证据");
    try {
      PayTccDispatchVO user = bridge.recover(execution.payNo);
      String state = evidence.classify(execution, user);
      return executions.observeSnapshot(
          execution, state, "UNKNOWN".equals(state) ? "两端永久终态尚未齐全" : null);
    } catch (BizException failure) {
      rethrowConflict(failure);
      return executions.observeSnapshot(execution, "UNKNOWN", failure.getCode());
    } catch (RuntimeException unavailable) {
      return executions.observeSnapshot(
          execution, "UNKNOWN", unavailable.getClass().getSimpleName());
    }
  }

  /** 禁止把新的全局事务误加入别人的 XID 或尚未提交的本地事务。 */
  private void requireNoContext() {
    if (RootContext.getXID() != null
        || TransactionSynchronizationManager.isActualTransactionActive())
      throw new BizException("PAY_TCC_CONTEXT_INVALID", "Coordinator 不允许嵌套事务上下文", 409);
  }
}
