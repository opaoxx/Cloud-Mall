package com.cloudmall.pay.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.mapper.PayCallbackLogMapper;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PayProtocolMetrics.*;
import com.cloudmall.pay.service.PayService;
import com.cloudmall.pay.util.PayDebitModes;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api/payments")
public class PayServiceImpl implements PayService {
  /** 正式容器强制注入真实观察组件，旧直接构造保持无资源兼容。 */
  @Autowired private PayProtocolMetrics metrics = PayProtocolMetrics.noop();

  /** 保存 orders 的业务状态或配置。 */
  @Autowired private OrderClient orders;

  /** 保存 users 的业务状态或配置。 */
  @Autowired private UserClient users;

  /** 保存 db 的业务状态或配置。 */
  @Autowired private PayRecordMapper payRecordMapper;

  /** 支付回调日志 Mapper。 */
  @Autowired private PayCallbackLogMapper payCallbackLogMapper;

  /** Legacy失败授权在独立事务中重读并锁定当前事实。 */
  @Autowired private PayLegacyFailureService legacyFailureService;

  /** 跨用户账本和订单服务的持久支付恢复编排。 */
  @Autowired private PayConsistencyService payConsistencyService;

  /** 显式启用后才提供的 TCC 公开支付编排。 */
  @Autowired private ObjectProvider<PayTccPaymentService> tccPaymentService;

  /** 在远程副作用前独立提交回调绑定。 */
  @Autowired private PayCallbackReceiptService callbackReceipts;

  /** 判断失败回调能否安全取消订单的持久意图 Mapper。 */
  @Autowired private PayDebitIntentMapper payDebitIntentMapper;

  /** 生产强制注入共享监视器；直接构造时保持单实例原互斥语义。 */
  @Autowired private PayWriteMonitor writeMonitor = new PayWriteMonitor();

  @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}")
  /** 保存 callbackToken 的业务状态或配置。 */
  private String callbackToken;

  /** 创建由 Spring 字段注入依赖的支付服务实例。 */
  public PayServiceImpl() {}

  /** 创建 PayServiceImpl 实例。 */
  public PayServiceImpl(
      OrderClient orders,
      UserClient users,
      PayRecordMapper payRecordMapper,
      PayCallbackLogMapper payCallbackLogMapper) {
    // 1. 接收并整理 PayController 的业务请求。
    // 2. 执行 PayController 的核心业务校验与状态处理。
    // 3. 返回 PayController 的处理结果。
    this.orders = orders;
    this.users = users;
    this.payRecordMapper = payRecordMapper;
    this.payCallbackLogMapper = payCallbackLogMapper;
  }

  @PostMapping("/callback")
  /** 执行 callback 相关操作。 */
  public ApiResponse<?> callback(
      @RequestHeader(value = "X-Internal-Callback-Token", required = false) String token,
      @RequestBody PayCallbackDTO callback) {
    synchronized (writeMonitor) {
      // 1. 接收并整理 callback 的业务请求。
      // 2. 执行 callback 的核心业务校验与状态处理。
      // 3. 返回 callback 的处理结果。
      if (!Objects.equals(callbackToken, token))
        throw new BizException(ErrorCodes.FORBIDDEN, "回调凭证无效", 403);
      if (callback == null
          || callback.payNo == null
          || callback.payNo.isBlank()
          || callback.amount == null
          || callback.userId == null) {
        throw new BizException(ErrorCodes.INVALID, "回调参数不完整", 400);
      }
      String callbackId =
          callback.callbackId == null || callback.callbackId.isBlank()
              ? callback.payNo
              : callback.callbackId;
      PayVO payment = ownedForUser(callback.payNo, callback.userId);
      String mode = paymentMode(callback.payNo);
      if (payment.amount.compareTo(callback.amount) != 0)
        throw new BizException(ErrorCodes.PAY_MISMATCH, "回调金额不一致", 409);
      metrics.route(PayProtocolMetrics.mode(mode), Entry.CALLBACK);
      callbackReceipts.claim(payment, callback, callbackId);
      if (callback.success) {
        if ("FAILED".equals(payment.status) || "REFUNDED".equals(payment.status))
          throw new BizException(ErrorCodes.PAY_DONE, "支付已处理", 409);
        if (!"SUCCESS".equals(payment.status)) {
          String outcome = initiate(payment, mode);
          if (!"SUCCESS".equals(outcome)) return responseForOutcome(payment.payNo, outcome);
        }
      } else if (PayDebitModes.TCC.equals(mode)) {
        tccEntry().failure(payment);
      } else {
        payment = legacyFailureService.fail(payment);
      }
      callbackReceipts.complete(payment, callback, callbackId);
      return ApiResponse.ok(find(payment.payNo));
    }
  }

  /** 将可恢复中的支付结果映射为明确的 HTTP 业务状态。 */
  private ApiResponse<?> responseForOutcome(String payNo, String outcome) {
    return ApiResponse.ok(PayProcessingSupport.paymentForOutcome(payRecordMapper, payNo, outcome));
  }

  /** 在回调、模拟成功或失败产生副作用前核验原支付协议。 */
  private String paymentMode(String payNo) {
    return PayProcessingSupport.paymentMode(payRecordMapper, payDebitIntentMapper, payNo);
  }

  /** 依据持久协议选择唯一的资金编排入口。 */
  private String initiate(PayVO payment, String mode) {
    return PayProcessingSupport.initiate(payment, mode, payConsistencyService, tccPaymentService);
  }

  /** 缺失显式入口依赖时关闭 TCC 路由，不降级为 Legacy。 */
  private PayTccPaymentService tccEntry() {
    return PayProcessingSupport.tccEntry(tccPaymentService);
  }

  /** 委托原支付归属检查，不改变读取和异常顺序。 */
  private PayVO ownedForUser(String payNo, long userId) {
    return PayProcessingSupport.ownedForUser(payRecordMapper, payNo, userId);
  }

  /** 委托原支付读取及首条/空结果规则。 */
  private PayVO find(String payNo) {
    return PayProcessingSupport.find(payRecordMapper, payNo);
  }
}
