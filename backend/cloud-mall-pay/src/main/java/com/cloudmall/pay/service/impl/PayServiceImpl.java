package com.cloudmall.pay.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
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
import java.util.List;
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

  @PostMapping("/{payNo}/mock-success")
  /** 执行 success 相关操作。 */
  public ApiResponse<?> success(@PathVariable String payNo) {
    synchronized (writeMonitor) {
      // 1. 接收并整理 success 的业务请求。
      // 2. 执行 success 的核心业务校验与状态处理。
      // 3. 返回 success 的处理结果。
      PayVO payment = owned(payNo);
      String mode = paymentMode(payNo);
      metrics.route(PayProtocolMetrics.mode(mode), Entry.MOCK_SUCCESS);
      if ("SUCCESS".equals(payment.status)) return ApiResponse.ok(payment);
      if ("FAILED".equals(payment.status) || "REFUNDED".equals(payment.status))
        throw new BizException(ErrorCodes.PAY_DONE, "支付已处理", 409);
      String outcome = initiate(payment, mode);
      return responseForOutcome(payNo, outcome);
    }
  }

  @PostMapping("/{payNo}/mock-fail")
  /** 按持久协议处理失败请求，已失败重放和退款中状态不重复取消订单。 */
  public ApiResponse<?> fail(@PathVariable String payNo) {
    // 1. 读取当前用户支付并核验不可变协议。
    PayVO payment = owned(payNo);
    String mode = paymentMode(payNo);
    metrics.route(PayProtocolMetrics.mode(mode), Entry.MOCK_FAIL);
    if (PayDebitModes.TCC.equals(mode)) {
      tccEntry().failure(payment);
      return ApiResponse.ok(payment);
    }
    // 2. 独立当前读事务负责授权和取消；不使用外层旧视图决定无意图。
    return ApiResponse.ok(legacyFailureService.fail(payment));
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
    if ("SUCCESS".equals(outcome)) return ApiResponse.ok(find(payNo));
    if ("REFUNDED".equals(outcome) || "FAILED".equals(outcome))
      throw new BizException("PAYMENT_FAILED", "订单未完成支付，余额已按账本处理", 409);
    if ("REFUND_PENDING".equals(outcome))
      throw new BizException("PAY_REFUND_PENDING", "订单已取消，余额退款处理中", 202);
    throw new BizException("PAY_ORDER_SYNC_UNKNOWN", "扣款或订单结果待确认", 202);
  }

  /** 在回调、模拟成功或失败产生副作用前核验原支付协议。 */
  private String paymentMode(String payNo) {
    List<PayRecordPO> records = payRecordMapper.selectByPayNo(payNo);
    if (records.size() != 1) throw PayDebitModes.unavailable();
    String mode = records.get(0).debitMode;
    PayDebitIntentPO intent = payDebitIntentMapper.findByPayNo(payNo);
    if (PayDebitModes.TCC.equals(mode)) {
      if (intent != null) throw PayDebitModes.unavailable();
      return mode;
    }
    PayDebitModes.requireSupported(mode);
    if (intent != null) PayDebitModes.requireSupported(intent.debitMode);
    return PayDebitModes.LEGACY;
  }

  /** 依据持久协议选择唯一的资金编排入口。 */
  private String initiate(PayVO payment, String mode) {
    return PayDebitModes.TCC.equals(mode)
        ? tccEntry().pay(payment)
        : payConsistencyService.initiate(payment);
  }

  /** 缺失显式入口依赖时关闭 TCC 路由，不降级为 Legacy。 */
  private PayTccPaymentService tccEntry() {
    PayTccPaymentService entry = tccPaymentService.getIfAvailable();
    if (entry == null) throw PayDebitModes.unavailable();
    return entry;
  }

  /** 执行 owned 相关操作。 */
  private PayVO owned(String orderNo) {
    // 1. 接收并整理 owned 的业务请求。
    // 2. 执行 owned 的核心业务校验与状态处理。
    // 3. 返回 owned 的处理结果。
    return ownedForUser(orderNo, AuthContext.requireUserId());
  }

  /** 执行 ownedForUpdate 相关操作。 */
  private PayVO ownedForUpdate(String orderNo) {
    // 1. 接收并整理 ownedForUpdate 的业务请求。
    // 2. 执行 ownedForUpdate 的核心业务校验与状态处理。
    // 3. 返回 ownedForUpdate 的处理结果。
    List<PayVO> records =
        payRecordMapper.selectForUpdate(orderNo).stream().map(PayServiceImpl::toView).toList();
    if (records.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "支付记录不存在", 404);
    PayVO p = records.get(0);
    if (!Objects.equals(p.userId, AuthContext.requireUserId()))
      throw new BizException(ErrorCodes.FORBIDDEN, "无权访问此支付记录", 403);
    return p;
  }

  /** 执行 ownedForUser 相关操作。 */
  private PayVO ownedForUser(String orderNo, long userId) {
    // 1. 接收并整理 ownedForUser 的业务请求。
    // 2. 执行 ownedForUser 的核心业务校验与状态处理。
    // 3. 返回 ownedForUser 的处理结果。
    PayVO p = find(orderNo);
    if (!Objects.equals(p.userId, userId))
      throw new BizException(ErrorCodes.FORBIDDEN, "无权访问此支付记录", 403);
    return p;
  }

  /** 执行 find 相关操作。 */
  private PayVO find(String orderNo) {
    // 1. 接收并整理 find 的业务请求。
    // 2. 执行 find 的核心业务校验与状态处理。
    // 3. 返回 find 的处理结果。
    List<PayVO> records = findBy("pay_no", orderNo);
    if (records.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "支付记录不存在", 404);
    return records.get(0);
  }

  /** 执行 findBy 相关操作。 */
  private List<PayVO> findBy(String column, String value) {
    // 1. 接收并整理 findBy 的业务请求。
    // 2. 执行 findBy 的核心业务校验与状态处理。
    // 3. 返回 findBy 的处理结果。
    if ("pay_no".equals(column)) {
      return payRecordMapper.selectByPayNo(value).stream().map(PayServiceImpl::toView).toList();
    }
    if ("order_no".equals(column)) {
      return payRecordMapper.selectByOrderNo(value).stream().map(PayServiceImpl::toView).toList();
    }
    throw new IllegalArgumentException("Unsupported payment lookup column: " + column);
  }

  /** 将支付记录持久化对象转换为响应视图。 */
  private static PayVO toView(PayRecordPO paymentRecord) {
    return com.cloudmall.pay.util.PayRecordViews.toView(paymentRecord);
  }
}
