package com.cloudmall.pay.service.impl.legacy;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.util.PayDebitModes;
import com.cloudmall.pay.util.PayRecordViews;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Legacy 失败授权与首次 intent 共用 Pay→intent 行锁，拒绝旧快照推断无扣款。 */
@Service
public class PayLegacyFailureService {
  /** 原支付当前锁定读及失败 CAS。 */
  private final PayRecordMapper payments;

  /** 已提交扣款意图的当前锁定读。 */
  private final PayDebitIntentMapper intents;

  /** 仅在锁内明确允许时沿原路径取消订单。 */
  private final OrderClient orders;

  /** 创建独立失败事务，不依赖调用者的旧事务快照。 */
  public PayLegacyFailureService(
      PayRecordMapper payments, PayDebitIntentMapper intents, OrderClient orders) {
    this.payments = payments;
    this.intents = intents;
    this.orders = orders;
  }

  /** 锁内重读真实状态；订单取消和失败 CAS 同事务，返回当前事实视图。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayVO fail(PayVO expected) {
    // 1. 先锁 Pay，和 ensureIntent 一致；调用者视图仅用于身份/载荷核验。
    if (expected == null || expected.payNo == null || expected.payNo.isBlank())
      throw PayDebitModes.unavailable();
    List<PayRecordPO> rows = payments.selectForUpdate(expected.payNo);
    if (rows.size() != 1) throw PayDebitModes.unavailable();
    PayRecordPO payment = rows.get(0);
    PayDebitModes.requireSupported(payment.debitMode);
    if (!Objects.equals(payment.payNo, expected.payNo)
        || !Objects.equals(payment.userId, expected.userId)
        || !Objects.equals(payment.orderNo, expected.orderNo)
        || payment.amount == null
        || expected.amount == null
        || payment.amount.compareTo(expected.amount) != 0) throw PayDebitModes.unavailable();
    PayVO current = PayRecordViews.toView(payment);
    // 2. 锁定读已提交的 intent，不能由外层 RR 快照中的 null 授权取消。
    PayDebitIntentPO intent = intents.findByPayNoForUpdate(payment.payNo);
    if (intent != null) {
      PayDebitModes.requireSupported(intent.debitMode);
      if (!Objects.equals(intent.payNo, payment.payNo)
          || !Objects.equals(intent.userId, payment.userId)
          || !Objects.equals(intent.orderNo, payment.orderNo)
          || intent.amount == null
          || intent.amount.compareTo(payment.amount) != 0) throw PayDebitModes.unavailable();
    }
    if ("SUCCESS".equals(payment.status) || "REFUNDED".equals(payment.status))
      throw new BizException(ErrorCodes.PAY_DONE, "支付已处理", 409);
    if ("REFUND_PENDING".equals(payment.status))
      throw new BizException("PAY_REFUND_PENDING", "订单已取消，余额退款处理中", 202);
    boolean failedIntent = intent != null && "FAILED".equals(intent.status);
    if ("FAILED".equals(payment.status) && (intent == null || failedIntent)) return current;
    boolean canCancel =
        ("PENDING".equals(payment.status) && (intent == null || failedIntent))
            || ("ORDER_SYNC_UNKNOWN".equals(payment.status) && failedIntent);
    if (!canCancel) throw new BizException("PAY_ORDER_SYNC_UNKNOWN", "支付结果正在确认，不能取消订单", 202);
    // 3. 持有原 Pay 锁直至原取消调用和本地 CAS 完成，阻止新 intent 交错插入。
    cancelOrder(current);
    if (payments.markFailed(payment.payNo, LocalDateTime.now(ZoneId.of("Asia/Shanghai"))) != 1)
      throw new BizException(ErrorCodes.PAY_DONE, "支付状态已被处理", 409);
    payment.status = "FAILED";
    return PayRecordViews.toView(payment);
  }

  /** 保留原 Order RPC 的业务异常和其他异常 500 映射。 */
  private void cancelOrder(PayVO payment) {
    try {
      ApiResponse<?> response = orders.cancel(payment.orderNo, payment.userId);
      if (response == null || !"0".equals(response.code))
        throw new BizException(ErrorCodes.INTERNAL, "订单失败回滚未确认", 500);
    } catch (BizException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new BizException(ErrorCodes.INTERNAL, "订单失败回滚失败", 500);
    }
  }
}
