package com.cloudmall.user.service.impl;

import com.cloudmall.user.domain.dto.BalanceTccBranchDTO;
import com.cloudmall.user.domain.dto.BalanceTccDebitTryDTO;
import com.cloudmall.user.domain.po.BalanceDebitAttemptPO;
import com.cloudmall.user.domain.po.TccFenceLogPO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceTccResourceEvidenceMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import io.seata.core.context.RootContext;
import io.seata.rm.tcc.TCCFenceHandler;
import io.seata.rm.tcc.api.BusinessActionContext;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** User LocalTCC 适配器：官方 Fence 与永久业务事实始终在同一外层事务中提交。 */
public class BalanceTccDebitLocalResourceImpl implements BalanceTccDebitLocalResource {
  /** 记录需要 TC 重试的回调失败，不打印完整业务载荷。 */
  private static final Logger LOG = LoggerFactory.getLogger(BalanceTccDebitLocalResourceImpl.class);

  /** 官方 Fence 只反射本类指定的公开业务委托。 */
  private static final Method CONFIRM = callbackMethod("confirmBusiness");

  /** 官方 Fence 的公开取消委托。 */
  private static final Method CANCEL = callbackMethod("cancelBusiness");

  /** 统一 User 数据源的 REQUIRED 本地事务。 */
  private final TransactionTemplate transactions;

  /** 原资金状态机委托。 */
  private final BalanceTccDebitBusinessCallback callback;

  /** Fence 只读证据，不在适配器中复制 Fence SQL。 */
  private final TccFenceLogMapper fences;

  /** Fence 清理后仍保留的永久归属。 */
  private final BalanceDebitAttemptMapper attempts;

  /** 被唯一键拒绝分支的无资金证明。 */
  private final BalanceTccResourceEvidenceMapper evidence;

  /** 创建显式 Fence 资源，事务管理器必须已通过同数据源校验。 */
  public BalanceTccDebitLocalResourceImpl(
      PlatformTransactionManager manager,
      BalanceTccDebitBusinessCallback callback,
      TccFenceLogMapper fences,
      BalanceDebitAttemptMapper attempts,
      BalanceTccResourceEvidenceMapper evidence) {
    this.transactions = new TransactionTemplate(manager);
    this.callback = callback;
    this.fences = fences;
    this.attempts = attempts;
    this.evidence = evidence;
  }

  /** 分支登记完成后，原子提交官方 TRIED Fence 和真实余额冻结。 */
  @Override
  public boolean prepare(BusinessActionContext context, BalanceTccBranchDTO request) {
    // 1. 仅接受由当前 Seata 全局上下文绑定的分支。
    callback.payload(context);
    if (!Objects.equals(RootContext.getXID(), context.getXid()))
      throw new IllegalArgumentException("Try 缺少匹配的 Seata 上下文");
    // 2. 官方 Fence 内层加入同一个 REQUIRED 事务，业务异常整体回滚。
    return Boolean.TRUE.equals(
        transactions.execute(
            status ->
                TCCFenceHandler.prepareFence(
                    context.getXid(),
                    context.getBranchId(),
                    ACTION_NAME,
                    () -> callback.tryBusiness(context))));
  }

  /** TC 确认；任何未知结果都返回 false，不能仅凭 Fence 跳过账本核验。 */
  @Override
  public boolean commit(BusinessActionContext context) {
    return finish(context, true);
  }

  /** TC 回滚；空 Fence 也要在同事务中留下永久取消归属。 */
  @Override
  public boolean rollback(BusinessActionContext context) {
    return finish(context, false);
  }

  /** 回调事务独立于 Spring/Seata 代理顺序，异常和 false 均不得提交半完成事实。 */
  private boolean finish(BusinessActionContext context, boolean committing) {
    try {
      BalanceTccDebitTryDTO request = callback.payload(context);
      return Boolean.TRUE.equals(
          transactions.execute(
              status -> {
                // 1. Fence 清理后仅接受相同分支的已决业务事实，不推进未知状态。
                TccFenceLogPO fence = fences.findByBranch(context.getXid(), context.getBranchId());
                if (fence != null && !ACTION_NAME.equals(fence.actionName)) return false;
                if (fence == null && hasTerminalProof(request, committing))
                  return verifyBusiness(context, committing);
                // 2. 显式调用一次官方 Fence，保留官方乱序和防悬挂语义。
                boolean accepted =
                    committing
                        ? TCCFenceHandler.commitFence(
                            CONFIRM,
                            callback,
                            context.getXid(),
                            context.getBranchId(),
                            new Object[] {context})
                        : TCCFenceHandler.rollbackFence(
                            CANCEL,
                            callback,
                            context.getXid(),
                            context.getBranchId(),
                            new Object[] {context},
                            ACTION_NAME);
                if (!accepted) {
                  status.setRollbackOnly();
                  return false;
                }
                // 3. 自动 Fence 会跳过重复/空回调；此处补业务核验或永久取消事实。
                if (!committing && rejectedBranchHasNoFunds(request)) return true;
                boolean verified = verifyBusiness(context, committing);
                if (!verified) status.setRollbackOnly();
                return verified;
              }));
    } catch (RuntimeException failure) {
      LOG.warn("User TCC 清算尚未确认，等待 TC 重试，commit={}", committing, failure);
      return false;
    }
  }

  /** 只按本分支已决状态选择幂等核验；完整载荷及账本仍由原资金服务验证。 */
  private boolean hasTerminalProof(BalanceTccDebitTryDTO request, boolean committing) {
    BalanceDebitAttemptPO attempt = attempts.selectByPayNo(request.payNo);
    return attempt != null
        && "TCC".equals(attempt.mode)
        && Objects.equals(attempt.xid, request.xid)
        && Objects.equals(attempt.branchId, request.branchId)
        && (committing ? "CONFIRMED" : "CANCELLED").equals(attempt.state);
  }

  /** 只有 SUSPENDED 且本分支没有任何资金归属时，才可核对另一个永久所有者。 */
  private boolean rejectedBranchHasNoFunds(BalanceTccDebitTryDTO request) {
    TccFenceLogPO fence = fences.findByBranch(request.xid, request.branchId);
    if (fence == null
        || !Integer.valueOf(4).equals(fence.status)
        || evidence.countIncomingAttempts(request.xid, request.branchId) != 0
        || evidence.countIncomingTccDebits(request.xid, request.branchId) != 0) return false;
    for (BalanceDebitAttemptPO owner :
        evidence.findMatchingAttempts(
            request.xid,
            request.branchId,
            request.payNo,
            request.ledgerKey,
            request.operationKey)) {
      boolean known =
          "TCC".equals(owner.mode)
              ? Set.of("TRYING", "CONFIRMED", "CANCELLED").contains(owner.state)
              : "LEGACY".equals(owner.mode) && Set.of("APPLIED", "REJECTED").contains(owner.state);
      if (known
          && (!Objects.equals(owner.xid, request.xid)
              || !Objects.equals(owner.branchId, request.branchId))) return true;
    }
    return false;
  }

  /** 重复业务清算必须幂等且符合原载荷，否则不向 TC 报告成功。 */
  private boolean verifyBusiness(BusinessActionContext context, boolean committing) {
    return committing ? callback.confirmBusiness(context) : callback.cancelBusiness(context);
  }

  /** 固定版本只反射本项目公开 callback；方法不存在时启动即失败。 */
  private static Method callbackMethod(String name) {
    try {
      return BalanceTccDebitBusinessCallback.class.getMethod(name, BusinessActionContext.class);
    } catch (NoSuchMethodException missing) {
      throw new ExceptionInInitializerError(missing);
    }
  }
}
