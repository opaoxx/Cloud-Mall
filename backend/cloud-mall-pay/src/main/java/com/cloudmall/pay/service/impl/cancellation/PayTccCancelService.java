package com.cloudmall.pay.service.impl.cancellation;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayTccCancelPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.OrderClient;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 未建立资金执行的支付取消；先提交互斥事实，再核验订单和库存。 */
@Service
@ConditionalOnProperty(name = "cloudmall.pay.tcc-cancel.enabled", havingValue = "true")
@DependsOn("payTccCancelConfiguration")
public class PayTccCancelService {
  /** 唯一取消意图、租约和支付投影的短事务。 */
  private final PayTccCancelStore store;

  /** 原订单取消及其库存动作查询。 */
  private final OrderClient orders;

  /** 创建取消编排器，不依赖 User 或 TC 决议客户端。 */
  public PayTccCancelService(PayTccCancelStore store, OrderClient orders) {
    this.store = store;
    this.orders = orders;
  }

  /** 暂停调用方本地事务；只有原订单和库存均结清才正常返回。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void request(PayVO payment) {
    // 1. 取消权独立提交，先于任何远程请求。
    PayTccCancelPO job = store.claimOrRead(payment);
    // 2. 同一台账恢复，不重建 execution，不触及资金。
    String state = reconcile(job.payNo);
    // 3. 未决或冲突明确保留原支付，不把收到请求当作取消成功。
    if ("COMPLETED".equals(state)) return;
    if ("CONFLICT".equals(state)) throw conflict();
    throw pending();
  }

  /** 已持久化的任务可被调度器或其他实例继续处理，RPC 不持有本地事务。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public String reconcile(String payNo) {
    // 1. 随机 token 只控制本地写入，不能作为远端状态证明。
    String token = UUID.randomUUID().toString();
    PayTccCancelPO job = store.claimRecovery(payNo, token);
    if (job == null) {
      PayTccCancelPO existing = store.read(payNo);
      return existing == null ? "CANCEL_UNKNOWN" : existing.state;
    }
    String error = null;
    try {
      // 2. 先读完整订单事实，只有待支付订单才发出幂等取消。
      OrderClient.OrderView order = readOrder(job);
      if ("PENDING_PAYMENT".equals(order.status())
          && !"CONFLICT".equals(order.stockActionStatus())) {
        try {
          orders.cancel(job.orderNo, job.userId);
        } catch (RuntimeException responseLost) {
          error = responseLost.getClass().getSimpleName();
        }
        order = readOrder(job);
      }
      // 3. 不信任 POST 回包；订单取消且库存成功后才能原子完成 Pay/台账。
      String target;
      if (Set.of("PAID", "COMPLETED").contains(order.status())
          || "CONFLICT".equals(order.stockActionStatus())) target = "CONFLICT";
      else if ("CANCELLED".equals(order.status()))
        target = "SUCCEEDED".equals(order.stockActionStatus()) ? "COMPLETED" : "ORDER_CANCELLED";
      else target = "CANCEL_UNKNOWN";
      store.stage(payNo, token, target, error);
    } catch (BizException failure) {
      error = failure.getCode();
      store.stage(
          payNo,
          token,
          "PAY_TCC_CANCEL_CONFLICT".equals(error) ? "CONFLICT" : "CANCEL_UNKNOWN",
          error);
    } catch (RuntimeException unavailable) {
      error = unavailable.getClass().getSimpleName();
      store.stage(payNo, token, "CANCEL_UNKNOWN", error);
    } finally {
      store.finish(payNo, token, job.attempts, error);
    }
    return store.read(payNo).state;
  }

  /** 无法核验载荷为冲突；无响应仅未知，均不可猜测取消成功。 */
  private OrderClient.OrderView readOrder(PayTccCancelPO job) {
    ApiResponse<OrderClient.OrderView> response = orders.paymentState(job.orderNo, job.userId);
    if (response == null || !"0".equals(response.code) || response.data == null) throw pending();
    OrderClient.OrderView order = response.data;
    if (!Objects.equals(job.orderNo, order.orderNo())
        || !Objects.equals(job.userId, order.userId())
        || order.payAmount() == null
        || job.amount.compareTo(order.payAmount()) != 0) throw conflict();
    return order;
  }

  /** 取消尚未证实完成，保留原支付号等待同向恢复。 */
  private BizException pending() {
    return new BizException("PAY_ORDER_SYNC_UNKNOWN", "取消结果待确认，请保留原支付单", 202);
  }

  /** 相反订单或不可变载荷矛盾要求核验。 */
  private BizException conflict() {
    return new BizException("PAY_TCC_CANCEL_CONFLICT", "取消事实冲突，请核验原订单", 409);
  }
}
