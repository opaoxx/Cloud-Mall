package com.cloudmall.pay.service.impl.receipt;

import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayTccFencePO;
import com.cloudmall.pay.domain.po.PayTccReceiptPO;
import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import io.seata.core.context.RootContext;
import io.seata.rm.tcc.TCCFenceHandler;
import io.seata.rm.tcc.api.BusinessActionContext;
import java.lang.reflect.Method;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Pay receipt LocalTCC 适配器，显式组合官方 Fence 与 receipt callback。 */
public class PayTccReceiptLocalResourceImpl implements PayTccReceiptLocalResource {
  /** 待 TC 重试的清算错误，不输出完整业务载荷。 */
  private static final Logger LOG = LoggerFactory.getLogger(PayTccReceiptLocalResourceImpl.class);

  /** 官方 Fence 反射的本地确认委托。 */
  private static final Method CONFIRM = callbackMethod("confirmBusiness");

  /** 官方 Fence 反射的本地取消委托。 */
  private static final Method CANCEL = callbackMethod("cancelBusiness");

  /** 与 Fence 相同数据源的 REQUIRED 外层事务。 */
  private final TransactionTemplate transactions;

  /** 不涉及网络调用的 receipt 状态机。 */
  private final PayTccReceiptBusinessCallback callback;

  /** 官方 Fence 最新持久事实。 */
  private final PayTccFenceMapper fences;

  /** Fence 清理后仍保留的 receipt 分支归属。 */
  private final PayTccReceiptMapper receipts;

  /** 创建同一 Pay 数据源事务中的 receipt 资源。 */
  public PayTccReceiptLocalResourceImpl(
      PlatformTransactionManager transactionManager,
      PayTccReceiptBusinessCallback callback,
      PayTccFenceMapper fences,
      PayTccReceiptMapper receipts) {
    this.transactions = new TransactionTemplate(transactionManager);
    this.callback = callback;
    this.fences = fences;
    this.receipts = receipts;
  }

  /** 在同一 REQUIRED 事务中执行官方 Try Fence 和 Pay receipt Try。 */
  @Override
  public boolean prepare(BusinessActionContext context, PayTccDispatchDTO request) {
    // 1. 分支必须来自 Seata 代理绑定的原上下文。
    callback.payload(context);
    if (context == null || !Objects.equals(RootContext.getXID(), context.getXid())) {
      throw new IllegalArgumentException("Pay receipt 缺少匹配的 Seata XID");
    }
    // 2. 官方 Fence 和 receipt 业务同事务提交，异常整体回滚。
    return Boolean.TRUE.equals(
        transactions.execute(
            status ->
                TCCFenceHandler.prepareFence(
                    context.getXid(),
                    context.getBranchId(),
                    ACTION_NAME,
                    () -> callback.tryBusiness(context))));
  }

  /** 在同一事务中提交 Fence 和 receipt Confirm。 */
  @Override
  public boolean commit(BusinessActionContext context) {
    return finish(context, true);
  }

  /** 在同一事务中回滚 Fence 和 receipt Cancel。 */
  @Override
  public boolean rollback(BusinessActionContext context) {
    return finish(context, false);
  }

  /** 任一 Fence 或业务 callback 失败均回滚本地事务并返回 false。 */
  private boolean finish(BusinessActionContext context, boolean committing) {
    try {
      PayTccDispatchDTO request = callback.payload(context);
      return Boolean.TRUE.equals(
          transactions.execute(
              status -> {
                // 1. Fence 被清理时只核验已有永久终态，不复活未知分支。
                PayTccFencePO fence = fences.findByBranch(context.getXid(), context.getBranchId());
                if (fence != null && !hasSameFenceIdentity(fence, context)) return false;
                PayTccReceiptPO receipt = receipts.selectByPayNo(request.payNo);
                if (fence == null
                    && receipt != null
                    && Objects.equals(receipt.xid, context.getXid())
                    && Objects.equals(receipt.branchId, context.getBranchId())
                    && (committing ? "CONFIRMED" : "CANCELLED").equals(receipt.state))
                  return verify(context, committing);
                // 2. 官方回调与业务状态必须一同推进。
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
                // 3. 空/重复 Fence 会跳过业务委托，仍必须核验或补永久取消事实。
                if (!committing && rejectedBranchHasNoReceipt(context, request)) return true;
                boolean verified = verify(context, committing);
                if (!verified) status.setRollbackOnly();
                return verified;
              }));
    } catch (RuntimeException failure) {
      LOG.warn("Pay receipt 清算失败，等待 TC 重试，commit={}", committing, failure);
      return false;
    }
  }

  /** 未获归属的分支仅在 SUSPENDED 且无本分支 receipt 时安全 ACK，不触碰另一分支。 */
  private boolean rejectedBranchHasNoReceipt(
      BusinessActionContext context, PayTccDispatchDTO request) {
    PayTccFencePO fence = fences.findByBranch(context.getXid(), context.getBranchId());
    if (fence != null && !hasSameFenceIdentity(fence, context))
      throw new IllegalStateException("Pay receipt Fence 分支身份不一致");
    if (fence == null
        || !Integer.valueOf(4).equals(fence.status)
        || receipts.countByBranch(context.getXid(), context.getBranchId()) != 0) return false;
    for (PayTccReceiptPO owner :
        receipts.findOwners(
            request.payNo, context.getXid(), "tcc-debit:" + request.payNo, request.payNo))
      if (PayTccReceiptStore.hasValidStateAndBranch(owner)
          && !"CLAIMED".equals(owner.state)
          && hasValidOwnerPayload(owner)
          && hasExactOwnerKey(owner, request, context)
          && (!Objects.equals(owner.xid, context.getXid())
              || !Objects.equals(owner.branchId, context.getBranchId()))) return true;
    return false;
  }

  /** 完整载荷核对不能被 Fence 的重复 ACK 跳过。 */
  private boolean verify(BusinessActionContext context, boolean committing) {
    return committing ? callback.confirmBusiness(context) : callback.cancelBusiness(context);
  }

  /** 精确核对当前分支的Fence读回身份，CI查询别名不能充当原分支事实。 */
  private boolean hasSameFenceIdentity(PayTccFencePO fence, BusinessActionContext context) {
    return Objects.equals(fence.xid, context.getXid())
        && Objects.equals(fence.branchId, context.getBranchId())
        && ACTION_NAME.equals(fence.actionName);
  }

  /** 仅核原资源上下文要求的基本载荷域，不据此证明完整来源或资金结果。 */
  private boolean hasValidOwnerPayload(PayTccReceiptPO owner) {
    return owner.xid != null
        && !owner.xid.isBlank()
        && owner.userId != null
        && owner.userId > 0
        && owner.orderNo != null
        && !owner.orderNo.isBlank()
        && owner.amount != null
        && owner.amount.signum() > 0;
  }

  /** 保留四键OR归属语义，但CI别名必须至少有一个原唯一键精确命中。 */
  private boolean hasExactOwnerKey(
      PayTccReceiptPO owner, PayTccDispatchDTO request, BusinessActionContext context) {
    return Objects.equals(owner.payNo, request.payNo)
        || Objects.equals(owner.xid, context.getXid())
        || Objects.equals(owner.operationKey, "tcc-debit:" + request.payNo)
        || Objects.equals(owner.ledgerKey, request.payNo);
  }

  /** 固定只反射本项目公开 callback 方法。 */
  private static Method callbackMethod(String methodName) {
    try {
      return PayTccReceiptBusinessCallback.class.getMethod(methodName, BusinessActionContext.class);
    } catch (NoSuchMethodException missingMethod) {
      throw new ExceptionInInitializerError(missingMethod);
    }
  }
}
