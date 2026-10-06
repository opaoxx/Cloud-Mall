package com.cloudmall.pay.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayTccCancelPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccDeliveryVO;
import com.cloudmall.pay.domain.vo.PayTccExecutionVO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.PayTccDeliveryService;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 公开成功请求的持久 TCC 路由，不依赖 Legacy 一致性服务。 */
@Service
@ConditionalOnProperty(name = "cloudmall.pay.tcc-entry.enabled", havingValue = "true")
@DependsOn("payTccEntryConfiguration")
public class PayTccPaymentService {
  /** 取消功能关闭后仍必须保留的永久抢占事实。 */
  @Autowired private PayTccCancelMapper cancellations;

  /** 独立启用的主动取消编排，未启用时保持 16D 保守边界。 */
  @Autowired(required = false)
  private PayTccCancelService cancelService;

  /** 唯一一次 begin 和原执行恢复入口。 */
  private final PayTccCoordinatorService coordinator;

  /** 已确认资金的订单/库存交付与退款。 */
  private final PayTccDeliveryService delivery;

  /** 本地取消证据及支付状态投影。 */
  private final PayTccPublicStateStore states;

  /** Pay/User 回滚证据和 FAILED 投影服务。 */
  private final PayTccRollbackProjectionService rollbackProjection;

  /** 首次扣款前读取当前订单。 */
  private final OrderClient orders;

  /** 创建独立 TCC 公开路由。 */
  public PayTccPaymentService(
      PayTccCoordinatorService coordinator,
      PayTccDeliveryService delivery,
      PayTccPublicStateStore states,
      OrderClient orders,
      PayTccRollbackProjectionService rollbackProjection) {
    this.coordinator = coordinator;
    this.delivery = delivery;
    this.states = states;
    this.orders = orders;
    this.rollbackProjection = rollbackProjection;
  }

  /** 只有新 execution 校验待支付后 begin；既有执行永远只恢复原事务。 */
  public String pay(PayVO payment) {
    try {
      return reconcile(payment);
    } catch (feign.FeignException unavailable) {
      throw pending();
    }
  }

  /** 保留原资金执行，网络响应缺失不得被当作支付失败。 */
  private String reconcile(PayVO payment) {
    if (Set.of("SUCCESS", "FAILED", "REFUNDED").contains(payment.status)) return payment.status;
    PayTccExecutionVO execution;
    // 1. 已有执行不受后来订单取消阻断，仍必须清算原资金。
    if (states.execution(payment.payNo) != null) execution = coordinator.recover(payment.payNo);
    else {
      PayTccCancelPO cancellation = cancellations.selectByPayNo(payment.payNo);
      if (cancellation != null) {
        if ("COMPLETED".equals(cancellation.state))
          throw new BizException(ErrorCodes.PAY_DONE, "支付已取消", 409);
        if ("CONFLICT".equals(cancellation.state))
          throw new BizException("PAY_TCC_CANCEL_CONFLICT", "取消事实待核验", 409);
        throw pending();
      }
      ApiResponse<OrderClient.OrderView> response =
          orders.paymentState(payment.orderNo, payment.userId);
      if (response == null || !"0".equals(response.code) || response.data == null) throw pending();
      OrderClient.OrderView order = response.data;
      if (!Objects.equals(order.orderNo(), payment.orderNo)
          || !Objects.equals(order.userId(), payment.userId)
          || order.payAmount() == null
          || order.payAmount().compareTo(payment.amount) != 0) throw pending();
      if (!"PENDING_PAYMENT".equals(order.status())) throw pending();
      PayTccDispatchDTO request = new PayTccDispatchDTO();
      request.payNo = payment.payNo;
      request.userId = payment.userId;
      request.orderNo = payment.orderNo;
      request.amount = payment.amount;
      execution = coordinator.execute(request);
    }
    // 2. 已提交后仅走独立交付，不再次扣款。
    if (execution == null) throw pending();
    if ("COMMITTED".equals(execution.state)) {
      PayTccDeliveryVO result = delivery.deliver(payment.payNo);
      return result == null ? "UNKNOWN" : result.state;
    }
    // 3. 回滚 RPC/本地镜像不是单独依据；两端取消证据完整才投影 FAILED。
    if ("ROLLEDBACK".equals(execution.state)) return rollbackProjection.project(payment);
    return "UNKNOWN";
  }

  /** 已回滚执行只核验原事实；其余已有执行不接管，无执行沿用独立取消开关。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void failure(PayVO payment) {
    if ("SUCCESS".equals(payment.status) || "REFUNDED".equals(payment.status))
      throw new BizException(ErrorCodes.PAY_DONE, "支付已处理", 409);
    if ("FAILED".equals(payment.status)) return;
    // 1. 外层事务已暂停，只有原 execution 明确回滚才可读取 User 永久事实。
    PayTccExecutionPO execution = states.execution(payment.payNo);
    if (execution != null) {
      // 已进入退款交付时保留明确的退款中语义；失败请求不推进交付或再次退款。
      if ("COMMITTED".equals(execution.state) && "REFUND_PENDING".equals(payment.status))
        throw new BizException("PAY_REFUND_PENDING", "订单已取消，余额退款处理中", 202);
      if (!"ROLLEDBACK".equals(execution.state)) throw pending();
      try {
        // 2. 短事务投影成功后才更新返回视图，不读取外层旧 RR 快照。
        if (!"FAILED".equals(rollbackProjection.project(payment))) throw pending();
        payment.status = "FAILED";
        return;
      } catch (feign.FeignException unavailable) {
        throw pending();
      }
    }
    // 3. 没有资金执行时才允许 16E 的独立主动取消流程。
    if (cancelService != null) {
      cancelService.request(payment);
      // 已在独立事务核验并写入 FAILED；不要从挂起的旧 RR 快照重读。
      payment.status = "FAILED";
      return;
    }
    throw new BizException("PAY_ORDER_SYNC_UNKNOWN", "TCC 取消请求未执行，请通过订单取消并等待资金核验", 202);
  }

  private BizException pending() {
    return new BizException("PAY_ORDER_SYNC_UNKNOWN", "订单或资金结果待确认，请保留原支付单", 202);
  }
}
