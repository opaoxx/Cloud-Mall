package com.cloudmall.pay.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.dto.PayCreateDTO;
import com.cloudmall.pay.domain.po.PayCallbackLogPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.mapper.PayCallbackLogMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api/payments")
public class PayServiceImpl implements PayService {
  /** 保存 orders 的业务状态或配置。 */
  @Autowired private OrderClient orders;

  /** 保存 users 的业务状态或配置。 */
  @Autowired private UserClient users;

  /** 保存 db 的业务状态或配置。 */
  @Autowired private PayRecordMapper payRecordMapper;

  /** 支付回调日志 Mapper。 */
  @Autowired private PayCallbackLogMapper payCallbackLogMapper;

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
        || req.payAmount.signum() < 0) {
      throw new BizException(ErrorCodes.INVALID, "支付参数不完整", 400);
    }
    OrderClient.OrderView order = order(req.orderNo, userId);
    if (!Objects.equals(userId, order.userId()))
      throw new BizException(ErrorCodes.FORBIDDEN, "无权支付此订单", 403);
    if (!"PENDING_PAYMENT".equals(order.status()))
      throw new BizException(ErrorCodes.PAY_DONE, "订单不处于待支付状态", 409);
    if (req.payAmount.compareTo(order.payAmount()) != 0)
      throw new BizException(ErrorCodes.PAY_MISMATCH, "支付金额与订单应付金额不一致", 409);
    String payNo = payNoFor(userId, key);
    List<PayVO> keyed = findBy("pay_no", payNo);
    if (!keyed.isEmpty()) {
      PayVO p = keyed.get(0);
      if (!Objects.equals(p.orderNo, req.orderNo)
          || !Objects.equals(p.userId, userId)
          || p.amount.compareTo(req.payAmount) != 0) {
        throw new BizException(ErrorCodes.DUPLICATE, "Idempotency-Key已用于其他支付请求", 409);
      }
      return ApiResponse.ok(p);
    }
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

  @Transactional
  @PostMapping("/{payNo}/mock-success")
  /** 执行 success 相关操作。 */
  public synchronized ApiResponse<?> success(@PathVariable String payNo) {
    // 1. 接收并整理 success 的业务请求。
    // 2. 执行 success 的核心业务校验与状态处理。
    // 3. 返回 success 的处理结果。
    PayVO p = ownedForUpdate(payNo);
    if ("SUCCESS".equals(p.status)) return ApiResponse.ok(p);
    if (!"PENDING".equals(p.status)) throw new BizException(ErrorCodes.PAY_DONE, "支付已处理", 409);
    completeSuccess(p);
    payRecordMapper.markSuccess(
        payNo, java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
    return ApiResponse.ok(find(payNo));
  }

  @Transactional
  @PostMapping("/{payNo}/mock-fail")
  /** 执行 fail 相关操作。 */
  public ApiResponse<?> fail(@PathVariable String payNo) {
    // 1. 接收并整理 fail 的业务请求。
    // 2. 执行 fail 的核心业务校验与状态处理。
    // 3. 返回 fail 的处理结果。
    PayVO p = owned(payNo);
    if ("SUCCESS".equals(p.status)) throw new BizException(ErrorCodes.PAY_DONE, "支付已成功", 409);
    notifyFailure(p);
    if (payRecordMapper.markFailed(payNo, java.time.LocalDateTime.now()) != 1) {
      throw new BizException(ErrorCodes.PAY_DONE, "支付状态已被处理", 409);
    }
    return ApiResponse.ok(find(payNo));
  }

  @Transactional
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
    if (!payCallbackLogMapper.selectByCallbackId(callbackId).isEmpty()) {
      return ApiResponse.ok(find(callback.payNo));
    }
    PayVO p = ownedForUser(callback.payNo, callback.userId);
    if (p.amount.compareTo(callback.amount) != 0)
      throw new BizException(ErrorCodes.PAY_MISMATCH, "回调金额不一致", 409);
    if ("PENDING".equals(p.status)) {
      if (callback.success) completeSuccess(p);
      else notifyFailure(p);
      if (payRecordMapper.markCallback(
              p.payNo,
              callback.success ? "SUCCESS" : "FAILED",
              callback.success,
              java.time.LocalDateTime.now(),
              java.time.LocalDateTime.now())
          != 1) {
        throw new BizException(ErrorCodes.PAY_DONE, "支付状态已被处理", 409);
      }
    } else if ("SUCCESS".equals(p.status) && !callback.success) {
      throw new BizException(ErrorCodes.PAY_DONE, "支付已成功", 409);
    }
    PayCallbackLogPO callbackLog = new PayCallbackLogPO();
    callbackLog.id = id();
    callbackLog.payNo = p.payNo;
    callbackLog.callbackId = callbackId;
    callbackLog.callbackStatus = callback.success ? "SUCCESS" : "FAILED";
    callbackLog.payload = "{}";
    callbackLog.processedAt = java.time.LocalDateTime.now();
    callbackLog.createdAt = callbackLog.processedAt;
    payCallbackLogMapper.insert(callbackLog);
    return ApiResponse.ok(find(p.payNo));
  }

  /** 执行 completeSuccess 相关操作。 */
  private void completeSuccess(PayVO p) {
    // 1. 接收并整理 completeSuccess 的业务请求。
    // 2. 执行 completeSuccess 的核心业务校验与状态处理。
    // 3. 返回 completeSuccess 的处理结果。
    ApiResponse<UserClient.BalanceView> debit =
        users.debit(p.userId, p.userId, new UserClient.DebitRequest(p.payNo, p.amount));
    if (debit == null || !"0".equals(debit.code))
      throw new BizException("PAYMENT_FAILED", "余额不足", 409);
    ApiResponse<OrderClient.OrderView> paid = orders.paid(p.orderNo, p.userId);
    if (paid == null || !"0".equals(paid.code))
      throw new BizException(ErrorCodes.INTERNAL, "订单支付状态同步失败", 500);
  }

  /** 执行 notifyFailure 相关操作。 */
  private void notifyFailure(PayVO p) {
    // 1. 接收并整理 notifyFailure 的业务请求。
    // 2. 执行 notifyFailure 的核心业务校验与状态处理。
    // 3. 返回 notifyFailure 的处理结果。
    try {
      ApiResponse<?> response = orders.cancel(p.orderNo, p.userId);
      if (response == null || !"0".equals(response.code))
        throw new BizException(ErrorCodes.INTERNAL, "订单失败回滚未确认", 500);
    } catch (BizException e) {
      throw e;
    } catch (Exception e) {
      throw new BizException(ErrorCodes.INTERNAL, "订单失败回滚失败", 500);
    }
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
    PayVO payment = new PayVO();
    payment.payNo = paymentRecord.payNo;
    payment.orderNo = paymentRecord.orderNo;
    payment.userId = paymentRecord.userId;
    payment.amount = paymentRecord.amount;
    payment.status = paymentRecord.status;
    if (paymentRecord.paidAt != null) {
      payment.paidAt = paymentRecord.paidAt.atOffset(ZoneOffset.ofHours(8)).toString();
    }
    return payment;
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
