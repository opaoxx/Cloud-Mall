package com.cloudmall.pay.service.impl.execution;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.PayTccExecutionVO;
import com.cloudmall.pay.domain.vo.PayTccReceiptVO;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PayProtocolMetrics.*;
import com.cloudmall.pay.service.PayTccBridgeService;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.PayTccReceiptService;
import com.cloudmall.pay.service.PayTccTransactionClient;
import com.cloudmall.pay.service.impl.receipt.PayTccExecutionEvidence;
import io.seata.core.context.RootContext;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 一次性启动、不可变决议、只核对原资金事实的内部 coordinator。 */
@Service
@ConditionalOnProperty(name = "cloudmall.pay.tcc-coordinator.enabled", havingValue = "true")
public class PayTccCoordinatorServiceImpl implements PayTccCoordinatorService {
  /** 正式容器强制注入真实观察组件，旧直接构造保持无资源兼容。 */
  @Autowired private PayProtocolMetrics metrics = PayProtocolMetrics.noop();

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
    // 1. 原方法保留全部业务校验、调用与清理顺序。
    try {
      PayTccExecutionVO result = executeObserved(request);
      // 2. 原方法返回后仅按原结果记录一次有限观察。
      metrics.observation(
          Mode.TCC,
          Scope.TCC_EXECUTE,
          PayProtocolMetrics.executionOutcome(result == null ? null : result.state));
      return result;
    } catch (RuntimeException failure) {
      // 3. 原异常只作有限分类并保持同一对象传播。
      metrics.observation(Mode.TCC, Scope.TCC_EXECUTE, PayProtocolMetrics.failureOutcome(failure));
      throw failure;
    }
  }

  /** 保留原方法全部校验、调用顺序和finally，外层仅观察最终返回或原异常。 */
  private PayTccExecutionVO executeObserved(PayTccDispatchDTO request) {
    requireNoContext();
    try {
      return executeWithoutCallerContext(request);
    } finally {
      // 认领和末次快照校验也属于本入口，异常不得把依赖遗留的XID带回调用线程。
      RootContext.unbind();
    }
  }

  /** 入口已拒绝caller上下文；原begin绑定仍在无上下文观察之前清理。 */
  private PayTccExecutionVO executeWithoutCallerContext(PayTccDispatchDTO request) {
    // 1. 持久化执行权先于 begin，重放不能继承初次线程的执行权。
    PayTccExecutionPO owned = executions.claimOwned(request);
    if (owned == null) return recover(request.payNo);
    try {
      String xid = transactions.begin();
      if (xid == null || !Objects.equals(xid, RootContext.getXID()))
        throw new IllegalStateException("TC begin 未绑定原 XID");
      owned = executions.bindOwnedXid(owned, xid);
      requireOwnedContext(xid);
      // 2. 两分支仅初次执行；明确冲突停止，普通失败请求回滚而非声称已回滚。
      String decision = "ROLLBACK";
      try {
        PayTccReceiptVO receipt = receipts.prepare(request);
        requireOwnedContext(xid);
        if ("TRYING".equals(receipt.state)) {
          PayTccDispatchVO user = bridge.dispatch(request);
          requireOwnedContext(xid);
          if ("TRYING".equals(evidence.classify(executions.read(request.payNo), user)))
            decision = "COMMIT";
        }
      } catch (RuntimeException tryFailure) {
        rethrowConflict(tryFailure);
        requireOwnedContext(xid);
        recordObservation(owned, "UNKNOWN", tryFailure.getClass().getSimpleName());
      }
      requireOwnedContext(xid);
      owned = executions.decideOwned(owned, xid, decision);
      requireOwnedContext(xid);
      // 3. 决议独立提交后才发 RPC，之后永远不能改变方向。
      sendDecision(owned, xid);
    } catch (Exception beginOrPersistenceFailure) {
      rethrowConflict(beginOrPersistenceFailure);
      recordObservation(owned, "UNKNOWN", beginOrPersistenceFailure.getClass().getSimpleName());
    } finally {
      // 4. 入口已拒绝继承上下文，因此只清理本次 begin 建立的绑定。
      RootContext.unbind();
    }
    return PayTccExecutionVO.from(observe(executions.verifyDecisionSnapshot(owned)));
  }

  /** 只读核对两端；已有决议可用原 XID 重试，NONE 永不自动决议。 */
  @Override
  public PayTccExecutionVO recover(String payNo) {
    // 1. 原方法保留全部业务校验、调用与清理顺序。
    try {
      PayTccExecutionVO result = recoverObserved(payNo);
      // 2. 原方法返回后仅按原结果记录一次有限观察。
      metrics.observation(
          Mode.TCC,
          Scope.TCC_RECOVERY,
          PayProtocolMetrics.executionOutcome(result == null ? null : result.state));
      return result;
    } catch (RuntimeException failure) {
      // 3. 原异常只作有限分类并保持同一对象传播。
      metrics.observation(Mode.TCC, Scope.TCC_RECOVERY, PayProtocolMetrics.failureOutcome(failure));
      throw failure;
    }
  }

  /** 保留原方法全部校验、调用顺序和finally，外层仅观察最终返回或原异常。 */
  private PayTccExecutionVO recoverObserved(String payNo) {
    requireNoContext();
    try {
      // 1. 先核对持久事实；两个终态齐全时无需 TC 历史状态。
      PayTccExecutionPO current = observe(payNo);
      if (PayTccExecutionStore.terminal(current.state)
          || current.xid == null
          || "NONE".equals(current.decision)) return PayTccExecutionVO.from(current);
      // 2. 恢复始终无全局上下文；同向RPC不获得新begin或Try权限。
      sendDecision(current, null);
      return PayTccExecutionVO.from(observe(executions.verifyDecisionSnapshot(current)));
    } finally {
      // 3. 入口已拒绝caller context，只清理本次恢复内依赖遗留的绑定。
      RootContext.unbind();
    }
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
  private void sendDecision(PayTccExecutionPO execution, String expectedXid) {
    PayTccExecutionPO current = executions.verifyDecisionSnapshot(execution);
    requireExpectedContext(expectedXid);
    if (PayTccExecutionStore.terminal(current.state)) return;
    try {
      if ("COMMIT".equals(execution.decision)) {
        try {
          transactions.commit(execution.xid);
        } catch (Exception failure) {
          metrics.rpc(
              expectedXid == null ? Origin.RECOVERY : Origin.INITIAL,
              Decision.COMMIT,
              RpcResult.EXCEPTION);
          throw failure;
        }
        metrics.rpc(
            expectedXid == null ? Origin.RECOVERY : Origin.INITIAL,
            Decision.COMMIT,
            RpcResult.RETURNED);
      } else if ("ROLLBACK".equals(execution.decision)) {
        try {
          transactions.rollback(execution.xid);
        } catch (Exception failure) {
          metrics.rpc(
              expectedXid == null ? Origin.RECOVERY : Origin.INITIAL,
              Decision.ROLLBACK,
              RpcResult.EXCEPTION);
          throw failure;
        }
        metrics.rpc(
            expectedXid == null ? Origin.RECOVERY : Origin.INITIAL,
            Decision.ROLLBACK,
            RpcResult.RETURNED);
      }
    } catch (Exception decisionResponseLost) {
      requireExpectedContext(expectedXid);
      recordObservation(execution, "UNKNOWN", decisionResponseLost.getClass().getSimpleName());
    }
    requireExpectedContext(expectedXid);
  }

  /** 读关联结果锁内核对原执行快照，网络未知不覆盖已替换的 execution。 */
  private PayTccExecutionPO observe(String payNo) {
    return observe(executions.read(payNo));
  }

  /** 原观察内核只消费调用方已核对的快照；不按支付号重新取得其它owner。 */
  private PayTccExecutionPO observe(PayTccExecutionPO execution) {
    try {
      requireExpectedContext(null);
      if (PayTccExecutionStore.terminal(execution.state)) return execution;
      if (execution.xid == null)
        return recordUnboundObservation(execution, "UNKNOWN", "begin XID 尚无持久证据");
      try {
        PayTccDispatchVO user = bridge.recover(execution.payNo);
        requireExpectedContext(null);
        String state = evidence.classify(execution, user);
        requireExpectedContext(null);
        return recordUnboundObservation(
            execution, state, "UNKNOWN".equals(state) ? "两端永久终态尚未齐全" : null);
      } catch (BizException failure) {
        rethrowConflict(failure);
        return recordUnboundObservation(execution, "UNKNOWN", failure.getCode());
      } catch (RuntimeException unavailable) {
        return recordUnboundObservation(
            execution, "UNKNOWN", unavailable.getClass().getSimpleName());
      }
    } finally {
      // 只读观察由无context入口调用，清理回包或metadata返回时遗留的绑定。
      RootContext.unbind();
    }
  }

  /** 无全局上下文的观察在写前后均核对，不把依赖遗留XID带出本次观察。 */
  private PayTccExecutionPO recordUnboundObservation(
      PayTccExecutionPO execution, String state, String error) {
    requireExpectedContext(null);
    PayTccExecutionPO observed = recordObservation(execution, state, error);
    requireExpectedContext(null);
    return observed;
  }

  /** NONE保留原进度合并，已决议快照在锁内核方向后才写观察。 */
  private PayTccExecutionPO recordObservation(
      PayTccExecutionPO execution, String state, String error) {
    if ("NONE".equals(execution.decision))
      return executions.observeSnapshot(execution, state, error);
    return executions.observeDecidedSnapshot(execution, state, error);
  }

  /** 精确核caller期望上下文；NULL表示恢复/只读观察必须没有全局事务。 */
  private void requireExpectedContext(String expectedXid) {
    if (!Objects.equals(expectedXid, RootContext.getXID()))
      throw new BizException("PAY_TCC_EXECUTION_CONFLICT", "Coordinator 调用XID上下文已变化", 409);
  }

  /** 初次线程只在原begin上下文仍匹配时继续，冲突不降级为普通失败诊断。 */
  private void requireOwnedContext(String xid) {
    if (xid == null || !Objects.equals(xid, RootContext.getXID()))
      throw new BizException("PAY_TCC_EXECUTION_CONFLICT", "Coordinator 原XID上下文已变化", 409);
  }

  /** 禁止把新的全局事务误加入别人的 XID 或尚未提交的本地事务。 */
  private void requireNoContext() {
    if (RootContext.getXID() != null
        || TransactionSynchronizationManager.isActualTransactionActive())
      throw new BizException("PAY_TCC_CONTEXT_INVALID", "Coordinator 不允许嵌套事务上下文", 409);
  }
}
