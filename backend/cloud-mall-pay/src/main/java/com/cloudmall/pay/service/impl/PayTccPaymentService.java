package com.cloudmall.pay.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.vo.PayTccDeliveryVO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.PayTccExecutionVO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.PayTccDeliveryService;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

/** 公开成功请求的持久 TCC 路由，不依赖 Legacy 一致性服务。 */
@Service
@ConditionalOnProperty(name = "cloudmall.pay.tcc-entry.enabled", havingValue = "true")
@DependsOn("payTccEntryConfiguration")
public class PayTccPaymentService {
  /** 唯一一次 begin 和原执行恢复入口。 */
  private final PayTccCoordinatorService coordinator;

  /** 已确认资金的订单/库存交付与退款。 */
  private final PayTccDeliveryService delivery;

  /** 本地取消证据及支付状态投影。 */
  private final PayTccPublicStateStore states;

  /** 首次扣款前读取当前订单。 */
  private final OrderClient orders;

  /** 不以镜像缓存替代当前 User 取消证明。 */
  private final UserTccClient users;

  /** 内部 User 凭证，不对前端返回。 */
  private final String token;

  /** 创建独立 TCC 公开路由。 */
  public PayTccPaymentService(
      PayTccCoordinatorService coordinator,
      PayTccDeliveryService delivery,
      PayTccPublicStateStore states,
      OrderClient orders,
      UserTccClient users,
      @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}") String token) {
    this.coordinator = coordinator;
    this.delivery = delivery;
    this.states = states;
    this.orders = orders;
    this.users = users;
    this.token = token;
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
    if ("ROLLEDBACK".equals(execution.state)) {
      PayTccDispatchVO proof = states.cancelledProof(payment);
      ApiResponse<UserTccStateVO> response =
          users.state(
              payment.userId,
              proof.operationKey,
              payment.userId,
              "cloud-mall-pay",
              token,
              proof.xid);
      if (response == null || !"0".equals(response.code) || response.data == null) throw pending();
      UserTccStateVO user = response.data;
      if (!"CANCELLED".equals(user.state)
          || user.ledgerApplied
          || !Objects.equals(user.payNo, payment.payNo)
          || !Objects.equals(user.orderNo, payment.orderNo)
          || !Objects.equals(user.userId, payment.userId)
          || !Objects.equals(user.xid, proof.xid)
          || !Objects.equals(user.branchId, proof.userBranchId)
          || !Objects.equals(user.operationKey, proof.operationKey)
          || !Objects.equals(user.ledgerKey, proof.ledgerKey)
          || user.amount == null
          || new BigDecimal(user.amount).compareTo(payment.amount) != 0) throw pending();
      return states.projectFailed(payment, proof.xid);
    }
    return "UNKNOWN";
  }

  /** 本批 TCC 主动失败不执行取消或相反决议，明确返回未处理的 202。 */
  public void failure(PayVO payment) {
    if ("SUCCESS".equals(payment.status) || "REFUNDED".equals(payment.status))
      throw new BizException(ErrorCodes.PAY_DONE, "支付已处理", 409);
    if ("FAILED".equals(payment.status)) return;
    throw new BizException("PAY_ORDER_SYNC_UNKNOWN", "TCC 取消请求未执行，请通过订单取消并等待资金核验", 202);
  }

  private BizException pending() {
    return new BizException("PAY_ORDER_SYNC_UNKNOWN", "订单或资金结果待确认，请保留原支付单", 202);
  }
}
