package com.cloudmall.pay.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.config.PayDebitModeConfiguration;
import com.cloudmall.pay.domain.dto.PayCreateDTO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayCreationService;
import com.cloudmall.pay.util.PayRecordViews;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原支付创建事务、幂等重放和新支付模式选择。 */
@Service
public class PayCreationServiceImpl implements PayCreationService {
  /** 创建支付前读取订单归属、状态和应付金额的客户端。 */
  private final OrderClient orders;

  /** 原支付记录查询和插入入口。 */
  private final PayRecordMapper payRecordMapper;

  /** 仅用于新支付的协议选择，已有记录不重新选择。 */
  private final PayDebitModeConfiguration debitModeConfiguration;

  /** 与模拟成功和回调共用的本地操作监视器。 */
  private final PayWriteMonitor writeMonitor;

  /** 注入原创建依赖和唯一共享监视器。 */
  public PayCreationServiceImpl(
      OrderClient orders,
      PayRecordMapper payRecordMapper,
      PayDebitModeConfiguration debitModeConfiguration,
      PayWriteMonitor writeMonitor) {
    this.orders = orders;
    this.payRecordMapper = payRecordMapper;
    this.debitModeConfiguration = debitModeConfiguration;
    this.writeMonitor = writeMonitor;
  }

  /** {@inheritDoc} */
  @Override
  @Transactional
  public PayVO create(String idempotencyKey, PayCreateDTO request, LongSupplier userIdSupplier) {
    synchronized (writeMonitor) {
      // 1. 在事务开始且获得共享锁后读取身份，沿原次序校验请求。
      long userId = userIdSupplier.getAsLong();
      if (idempotencyKey == null
          || idempotencyKey.isBlank()
          || request == null
          || request.orderNo == null
          || request.payAmount == null
          || request.payAmount.signum() <= 0) {
        throw new BizException(ErrorCodes.INVALID, "支付参数不完整", 400);
      }
      // 2. 先按原用户和幂等键重放并核对载荷，不查询远端或重选模式。
      String payNo = payNoFor(userId, idempotencyKey);
      List<PayVO> keyedPayments = findBy("pay_no", payNo);
      if (!keyedPayments.isEmpty()) {
        PayVO payment = keyedPayments.get(0);
        if (!Objects.equals(payment.orderNo, request.orderNo)
            || !Objects.equals(payment.userId, userId)
            || payment.amount.compareTo(request.payAmount) != 0) {
          throw new BizException(ErrorCodes.DUPLICATE, "Idempotency-Key已用于其他支付请求", 409);
        }
        return payment;
      }
      // 3. 原订单归属、状态和金额核验后，才按订单号查已有支付。
      OrderClient.OrderView order = order(request.orderNo, userId);
      if (!Objects.equals(userId, order.userId()))
        throw new BizException(ErrorCodes.FORBIDDEN, "无权支付此订单", 403);
      if (!"PENDING_PAYMENT".equals(order.status()))
        throw new BizException(ErrorCodes.PAY_DONE, "订单不处于待支付状态", 409);
      if (request.payAmount.compareTo(order.payAmount()) != 0)
        throw new BizException(ErrorCodes.PAY_MISMATCH, "支付金额与订单应付金额不一致", 409);
      List<PayVO> existingPayments = findBy("order_no", request.orderNo);
      if (!existingPayments.isEmpty()) return existingPayments.get(0);
      // 4. 采样原时间和主键，冻结新支付模式并写入，然后按原支付号重读。
      OffsetDateTime createdAt = now();
      PayRecordPO paymentRecord = new PayRecordPO();
      paymentRecord.id = id();
      paymentRecord.payNo = payNo;
      paymentRecord.orderNo = request.orderNo;
      paymentRecord.userId = userId;
      paymentRecord.amount = request.payAmount;
      paymentRecord.status = "PENDING";
      paymentRecord.debitMode = debitModeConfiguration.newPaymentMode();
      paymentRecord.createdAt = createdAt.toLocalDateTime();
      paymentRecord.updatedAt = createdAt.toLocalDateTime();
      payRecordMapper.insert(paymentRecord);
      return find(payNo);
    }
  }

  /** 读取原订单详情；仅原空响应/data转404，不增加外层业务码检查。 */
  private OrderClient.OrderView order(String orderNo, long userId) {
    ApiResponse<OrderClient.OrderView> response = orders.get(orderNo, userId);
    if (response == null || response.data == null)
      throw new BizException(ErrorCodes.NOT_FOUND, "订单不存在", 404);
    return response.data;
  }

  /** 按支付号完整映射后取首条；插入后的空读仍沿用原404。 */
  private PayVO find(String payNo) {
    List<PayVO> records = findBy("pay_no", payNo);
    if (records.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "支付记录不存在", 404);
    return records.get(0);
  }

  /** 保留两种原查询及全部行映射顺序，非法列名沿用原异常。 */
  private List<PayVO> findBy(String column, String value) {
    if ("pay_no".equals(column)) {
      return payRecordMapper.selectByPayNo(value).stream().map(PayRecordViews::toView).toList();
    }
    if ("order_no".equals(column)) {
      return payRecordMapper.selectByOrderNo(value).stream().map(PayRecordViews::toView).toList();
    }
    throw new IllegalArgumentException("Unsupported payment lookup column: " + column);
  }

  /** 使用原用户、冒号和未经trim的键生成SHA-256前48位小写十六进制。 */
  private static String payNoFor(long userId, String idempotencyKey) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest((userId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte value : hash) result.append(String.format("%02x", value));
      return result.substring(0, 48);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  /** 按原+08:00偏移采样支付创建时间。 */
  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }

  /** 沿用原UUID高64位的绝对值生成主键，不改变原极值语义。 */
  private static long id() {
    return Math.abs(UUID.randomUUID().getMostSignificantBits());
  }
}
