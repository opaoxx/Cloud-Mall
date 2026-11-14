package com.cloudmall.pay.service.impl.delivery;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayTccDeliveryPO;
import com.cloudmall.pay.domain.vo.PayTccDeliveryVO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PayProtocolMetrics.*;
import com.cloudmall.pay.service.PayTccDeliveryService;
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** TCC 扣款确认后的订单交付与唯一退款，和 Legacy 支付路径完全分离。 */
@Service
@ConditionalOnProperty(name = "cloudmall.pay.tcc-delivery.enabled", havingValue = "true")
public class PayTccDeliveryServiceImpl implements PayTccDeliveryService {
  /** 正式容器强制注入真实观察组件，旧直接构造保持无资源兼容。 */
  @Autowired private PayProtocolMetrics metrics = PayProtocolMetrics.noop();

  /** 原交付记录与本地支付状态的事务边界。 */
  private final PayTccDeliveryStore store;

  /** User 真实 TCC 只读证据。 */
  private final UserTccClient tcc;

  /** 已有账本查询与幂等退款客户端；本服务绝不调用 debit。 */
  private final UserClient users;

  /** 订单幂等 paid 及库存动作最终状态查询。 */
  private final OrderClient orders;

  /** 只用于 User 内部 TCC GET 的服务凭证。 */
  private final String callbackToken;

  /** 创建交付服务，网络调用均位于本地事务之外。 */
  public PayTccDeliveryServiceImpl(
      PayTccDeliveryStore store,
      UserTccClient tcc,
      UserClient users,
      OrderClient orders,
      @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}")
          String callbackToken) {
    this.store = store;
    this.tcc = tcc;
    this.users = users;
    this.orders = orders;
    this.callbackToken = callbackToken;
  }

  /** 按原 payNo 尝试一次交付，未知结果保留并退避，不生成新付款或退款键。 */
  @Override
  public PayTccDeliveryVO deliver(String payNo) {
    // 1. 原方法保留全部业务校验、调用与清理顺序。
    try {
      PayTccDeliveryVO result = deliverObserved(payNo);
      // 2. 原方法返回后仅按原结果记录一次有限观察。
      metrics.observation(
          Mode.TCC,
          Scope.TCC_DELIVERY,
          PayProtocolMetrics.deliveryOutcome(result == null ? null : result.state));
      return result;
    } catch (RuntimeException failure) {
      // 3. 原异常只作有限分类并保持同一对象传播。
      metrics.observation(Mode.TCC, Scope.TCC_DELIVERY, PayProtocolMetrics.failureOutcome(failure));
      throw failure;
    }
  }

  /** 保留原方法全部校验、调用顺序和finally，外层仅观察最终返回或原异常。 */
  private PayTccDeliveryVO deliverObserved(String payNo) {
    if (RootContext.getXID() != null
        || TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("交付不能继承全局或本地事务");
    // 1. 先独立保存可重试意图；已回滚/未确认 execution 不建立任务。
    PayTccDeliveryPO original = store.ensure(payNo);
    if (original == null) return null;
    if (PayTccDeliveryStore.terminal(original.state)) return PayTccDeliveryVO.from(original);
    String token = UUID.randomUUID().toString();
    PayTccDeliveryPO job = store.claim(payNo, token);
    if (job == null) return PayTccDeliveryVO.from(store.read(payNo));
    String error = null;
    try {
      // 2. 当前两个资源证据完整后才允许订单动作，退款也不省略此核对。
      UserClient.BalanceOperationView debit = verifyMoney(job);
      OrderClient.OrderView order = readOrder(job);
      boolean reconcileCancellation = "CANCEL_RECONCILING".equals(job.state);
      boolean cancelRequested = "CANCEL_REQUESTED".equals(job.state) || reconcileCancellation;
      if (cancelRequested) requireCancellableOrder(order, reconcileCancellation);
      if ("PENDING_PAYMENT".equals(order.status())) {
        if (debit.refundOperationKey() != null || "REFUND_PENDING".equals(job.state))
          throw conflict("存在退款事实或意图，禁止再次支付订单");
        // 取消方向持久化后只允许cancel；RPC前再次以当前租约核验原方向。
        if (!store.stage(job.payNo, token, cancelRequested ? job.state : "ORDER_SYNC_UNKNOWN"))
          return PayTccDeliveryVO.from(store.read(payNo));
        try {
          if (cancelRequested) orders.cancel(job.orderNo, job.userId);
          else orders.paid(job.orderNo, job.userId);
        } catch (RuntimeException responseUnknown) {
          /* 以紧接着的只读事实为准。 */
        }
        order = readOrder(job);
        if (cancelRequested) requireCancellableOrder(order, reconcileCancellation);
      }
      if (Set.of("PAID", "COMPLETED").contains(order.status())
          && "SUCCEEDED".equals(order.stockActionStatus())) {
        if (debit.refundOperationKey() != null || "REFUND_PENDING".equals(job.state))
          throw conflict("订单已支付但存在退款事实或意图");
        store.stage(job.payNo, token, "SUCCESS");
      } else if ("CANCELLED".equals(order.status())
          && "SUCCEEDED".equals(order.stockActionStatus())) {
        // 3. 再次核验取消和资金；固定键 POST 的重放负责校验完整原退款载荷。
        OrderClient.OrderView cancelled = readOrder(job);
        if (!"CANCELLED".equals(cancelled.status())
            || !"SUCCEEDED".equals(cancelled.stockActionStatus())) throw pending();
        debit = verifyMoney(job);
        if (debit.refundOperationKey() != null
            && !job.refundOperationKey.equals(debit.refundOperationKey()))
          throw conflict("存在其他退款键");
        if (!store.stage(job.payNo, token, "REFUND_PENDING"))
          return PayTccDeliveryVO.from(store.read(payNo));
        UserClient.BalanceView refund =
            required(
                users.refund(
                    job.userId,
                    job.userId,
                    new UserClient.RefundRequest(
                        job.refundOperationKey, job.payNo, job.payNo, job.orderNo, job.amount)));
        if (!job.refundOperationKey.equals(refund.operationKey())
            || !"APPLIED".equals(refund.state())) throw pending();
        UserClient.BalanceOperationView fact =
            required(users.operation(job.userId, job.refundOperationKey, job.userId));
        verifyLedger(fact, job.refundOperationKey, "REFUND", job.amount);
        store.stage(job.payNo, token, "REFUNDED");
      } else {
        if (!cancelRequested && !"REFUND_PENDING".equals(job.state))
          store.stage(job.payNo, token, "ORDER_SYNC_UNKNOWN");
        throw pending();
      }
    } catch (RuntimeException failure) {
      error =
          failure instanceof BizException business
              ? business.getCode()
              : failure.getClass().getSimpleName();
    } finally {
      // 4. 迟到执行者不能释放新 owner 的 lease，也不能覆盖新终态。
      store.finish(job.payNo, token, job.attempts == null ? 1 : job.attempts, error);
    }
    return PayTccDeliveryVO.from(store.read(payNo));
  }

  /** 严格取消拒绝相反终态；已发出paid的重协调允许按订单胜出事实收敛，库存冲突均保留。 */
  private void requireCancellableOrder(OrderClient.OrderView order, boolean allowPaidWinner) {
    if ((!allowPaidWinner && Set.of("PAID", "COMPLETED").contains(order.status()))
        || "CONFLICT".equals(order.stockActionStatus())) throw conflict("取消意图与订单或库存状态冲突");
  }

  /** 不使用 bridge 的终态缓存代替 User 当前持久证据。 */
  private UserClient.BalanceOperationView verifyMoney(PayTccDeliveryPO job) {
    PayTccDispatchVO original = store.proof(job);
    UserTccStateVO live =
        required(
            tcc.state(
                job.userId,
                "tcc-debit:" + job.payNo,
                job.userId,
                "cloud-mall-pay",
                callbackToken,
                job.xid));
    if (!"CONFIRMED".equals(live.state)
        || !live.ledgerApplied
        || !Objects.equals(live.payNo, job.payNo)
        || !Objects.equals(live.userId, job.userId)
        || !Objects.equals(live.orderNo, job.orderNo)
        || !Objects.equals(live.xid, job.xid)
        || !Objects.equals(live.branchId, original.userBranchId)
        || !Objects.equals(live.operationKey, "tcc-debit:" + job.payNo)
        || !Objects.equals(live.ledgerKey, job.payNo)
        || live.amount == null
        || new BigDecimal(live.amount).compareTo(job.amount) != 0) throw conflict("TCC 当前完整证据尚未确认");
    UserClient.BalanceOperationView debit =
        required(users.operation(job.userId, job.payNo, job.userId));
    verifyLedger(debit, job.payNo, "DEBIT", job.amount);
    return debit;
  }

  /** 校验响应来自原订单，金额和归属不能只依赖路径参数。 */
  private OrderClient.OrderView readOrder(PayTccDeliveryPO job) {
    OrderClient.OrderView order = required(orders.paymentState(job.orderNo, job.userId));
    if (!Objects.equals(order.orderNo(), job.orderNo)
        || !Objects.equals(order.userId(), job.userId)
        || order.payAmount() == null
        || order.payAmount().compareTo(job.amount) != 0) throw conflict("订单载荷与原扣款不一致");
    return order;
  }

  /** User 写接口完成全载荷核验后，账本读必须匹配键、类型、金额和状态。 */
  private void verifyLedger(
      UserClient.BalanceOperationView fact, String key, String type, BigDecimal amount) {
    if (!key.equals(fact.operationKey())
        || !type.equals(fact.type())
        || !"APPLIED".equals(fact.state())
        || fact.amount() == null
        || new BigDecimal(fact.amount()).compareTo(amount) != 0) throw conflict("用户账本与交付不一致");
  }

  private <T> T required(ApiResponse<T> response) {
    if (response == null || !"0".equals(response.code) || response.data == null) throw pending();
    return response.data;
  }

  private BizException pending() {
    return new BizException("PAY_TCC_DELIVERY_PENDING", "订单、库存或退款事实待确认", 503);
  }

  private BizException conflict(String message) {
    return new BizException("PAY_TCC_DELIVERY_CONFLICT", message, 409);
  }
}
