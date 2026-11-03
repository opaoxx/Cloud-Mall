package com.cloudmall.pay.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.config.PayDebitModeConfiguration;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.dto.PayCreateDTO;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
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

  /** 只为新支付单选择协议；既有记录从持久字段核验。 */
  @Autowired private PayDebitModeConfiguration debitModeConfiguration;

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

  @Transactional
  @PostMapping
  /** 执行 create 相关操作。 */
  public synchronized ApiResponse<?> create(
      @RequestHeader("Idempotency-Key") String key, @RequestBody PayCreateDTO req) {
    // 1. 接收并整理 create 的业务请求。
    // 2. 执行 create 的核心业务校验与状态处理。
    // 3. 返回 create 的处理结果。
    long userId = AuthContext.requireUserId();
    if (key == null
        || key.isBlank()
        || req == null
        || req.orderNo == null
        || req.payAmount == null
        || req.payAmount.signum() <= 0) {
      throw new BizException(ErrorCodes.INVALID, "支付参数不完整", 400);
    }
    String payNo = payNoFor(userId, key);
    List<PayVO> keyed = findBy("pay_no", payNo);
    if (!keyed.isEmpty()) {
      PayVO payment = keyed.get(0);
      if (!Objects.equals(payment.orderNo, req.orderNo)
          || !Objects.equals(payment.userId, userId)
          || payment.amount.compareTo(req.payAmount) != 0) {
        throw new BizException(ErrorCodes.DUPLICATE, "Idempotency-Key已用于其他支付请求", 409);
      }
      return ApiResponse.ok(payment);
    }
    OrderClient.OrderView order = order(req.orderNo, userId);
    if (!Objects.equals(userId, order.userId()))
      throw new BizException(ErrorCodes.FORBIDDEN, "无权支付此订单", 403);
    if (!"PENDING_PAYMENT".equals(order.status()))
      throw new BizException(ErrorCodes.PAY_DONE, "订单不处于待支付状态", 409);
    if (req.payAmount.compareTo(order.payAmount()) != 0)
      throw new BizException(ErrorCodes.PAY_MISMATCH, "支付金额与订单应付金额不一致", 409);
    List<PayVO> old = findBy("order_no", req.orderNo);
    if (!old.isEmpty()) return ApiResponse.ok(old.get(0));
    OffsetDateTime now = now();
    PayRecordPO paymentRecord = new PayRecordPO();
    paymentRecord.id = id();
    paymentRecord.payNo = payNo;
    paymentRecord.orderNo = req.orderNo;
    paymentRecord.userId = userId;
    paymentRecord.amount = req.payAmount;
    paymentRecord.status = "PENDING";
    paymentRecord.debitMode = debitModeConfiguration.newPaymentMode();
    paymentRecord.createdAt = now.toLocalDateTime();
    paymentRecord.updatedAt = now.toLocalDateTime();
    payRecordMapper.insert(paymentRecord);
    return ApiResponse.ok(find(payNo));
  }

  @GetMapping("/orders/{orderNo}")
  /** 执行 get 相关操作。 */
  public ApiResponse<?> get(@PathVariable String orderNo) {
    // 1. 接收并整理 get 的业务请求。
    // 2. 执行 get 的核心业务校验与状态处理。
    // 3. 返回 get 的处理结果。
    long userId = AuthContext.requireUserId();
    List<PayVO> records =
        payRecordMapper.selectByOrderNoAndUserId(orderNo, userId).stream()
            .map(PayServiceImpl::toView)
            .toList();
    if (records.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "支付记录不存在", 404);
    return ApiResponse.ok(records.get(0));
  }

  @PostMapping("/{payNo}/mock-success")
  /** 执行 success 相关操作。 */
  public synchronized ApiResponse<?> success(@PathVariable String payNo) {
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
  public synchronized ApiResponse<?> callback(
      @RequestHeader(value = "X-Internal-Callback-Token", required = false) String token,
      @RequestBody PayCallbackDTO callback) {
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

  /** 执行 order 相关操作。 */
  private OrderClient.OrderView order(String orderNo, long userId) {
    // 1. 接收并整理 order 的业务请求。
    // 2. 执行 order 的核心业务校验与状态处理。
    // 3. 返回 order 的处理结果。
    ApiResponse<OrderClient.OrderView> response = orders.get(orderNo, userId);
    if (response == null || response.data == null)
      throw new BizException(ErrorCodes.NOT_FOUND, "订单不存在", 404);
    return response.data;
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

  /** 执行 payNoFor 相关操作。 */
  private static String payNoFor(long userId, String key) {
    // 1. 接收并整理 payNoFor 的业务请求。
    // 2. 执行 payNoFor 的核心业务校验与状态处理。
    // 3. 返回 payNoFor 的处理结果。
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest((userId + ":" + key).getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte value : hash) result.append(String.format("%02x", value));
      return result.substring(0, 48);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 将支付记录持久化对象转换为响应视图。 */
  private static PayVO toView(PayRecordPO paymentRecord) {
    return com.cloudmall.pay.util.PayRecordViews.toView(paymentRecord);
  }

  /** 执行 now 相关操作。 */
  private static OffsetDateTime now() {
    // 1. 接收并整理 now 的业务请求。
    // 2. 执行 now 的核心业务校验与状态处理。
    // 3. 返回 now 的处理结果。
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }

  /** 执行 id 相关操作。 */
  private static long id() {
    // 1. 接收并整理 id 的业务请求。
    // 2. 执行 id 的核心业务校验与状态处理。
    // 3. 返回 id 的处理结果。
    return Math.abs(UUID.randomUUID().getMostSignificantBits());
  }
}
