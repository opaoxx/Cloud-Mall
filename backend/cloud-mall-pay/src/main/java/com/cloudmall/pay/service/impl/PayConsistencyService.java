package com.cloudmall.pay.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.util.PayDebitModes;
import feign.FeignException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 用持久支付意图、用户账本和订单事实收敛模拟余额支付。 */
@Service
public class PayConsistencyService {
  /** 支付结果未知和恢复重试日志。 */
  private static final Logger log = LoggerFactory.getLogger(PayConsistencyService.class);

  /** 与数据库 DATETIME(3) 语义一致的业务时区。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** 单笔退款金额必须无损落在数据库DECIMAL(18,2)容量内。 */
  private static final BigDecimal MAX_REFUND_MONEY = new BigDecimal("9999999999999999.99");

  /** 独立提交远程扣款前的不可变支付意图。 */
  private final PayDebitIntentService payDebitIntentService;

  /** 支付恢复状态和租约持久层。 */
  private final PayDebitIntentMapper payDebitIntentMapper;

  /** 原支付记录持久层。 */
  private final PayRecordMapper payRecordMapper;

  /** 用户余额账本内部 Feign 客户端。 */
  private final UserClient userClient;

  /** 订单状态查询与幂等同步 Feign 客户端。 */
  private final OrderClient orderClient;

  /** 创建支付一致性服务。 */
  public PayConsistencyService(
      PayDebitIntentService payDebitIntentService,
      PayDebitIntentMapper payDebitIntentMapper,
      PayRecordMapper payRecordMapper,
      UserClient userClient,
      OrderClient orderClient) {
    this.payDebitIntentService = payDebitIntentService;
    this.payDebitIntentMapper = payDebitIntentMapper;
    this.payRecordMapper = payRecordMapper;
    this.userClient = userClient;
    this.orderClient = orderClient;
  }

  /** 在调用用户服务前保存意图，并尝试完成一次支付同步。 */
  public String initiate(PayVO payment) {
    // 1. 独立提交 INIT，崩溃后恢复器仍能发现同一 payNo。
    PayDebitIntentPO intent = payDebitIntentService.ensureIntent(payment);
    // 2. 按数据库租约处理一次；其他实例处理时返回其当前可见状态。
    return process(intent.payNo);
  }

  /** 定时扫描未完成意图，处理远程响应丢失和进程中断。 */
  @Scheduled(fixedDelayString = "${cloudmall.pay.recovery-interval-ms:5000}")
  public void recoverDue() {
    // 1. 只读取达到重试时间的非终态意图。
    List<PayDebitIntentPO> due = payDebitIntentMapper.findDue(now(), 50);
    // 2. 每条意图以 payNo 独立认领和恢复，不阻塞后续记录。
    for (PayDebitIntentPO intent : due) {
      try {
        process(intent.payNo);
      } catch (RuntimeException recoveryFailure) {
        log.warn("支付恢复任务本轮失败，下一轮继续处理，payNo={}", intent.payNo, recoveryFailure);
      }
    }
  }

  /** 返回支付意图的当前状态，必要时执行一次可恢复处理。 */
  public String process(String payNo) {
    // 1. 原意图与支付单模式先核对，再领取租约；拒绝路径不修改任何恢复字段。
    PayDebitIntentPO pending = payDebitIntentMapper.findByPayNo(payNo);
    if (pending != null) requireLegacyRoute(pending);
    LocalDateTime currentTime = now();
    // 2. 每次领取使用独立身份，同实例迟到旧执行不得匹配重领后的owner。
    String claimToken = UUID.randomUUID().toString();
    if (payDebitIntentMapper.claim(payNo, claimToken, currentTime.plusSeconds(30), currentTime)
        != 1) {
      PayDebitIntentPO existing = payDebitIntentMapper.findByPayNo(payNo);
      if (existing != null) requireLegacyRoute(existing);
      return existing == null ? "INIT" : existing.status;
    }
    // 3. 领取成功后仅以本次身份进入处理，后续条件写不能复用实例标识。
    PayDebitIntentPO intent = payDebitIntentMapper.findByPayNo(payNo);
    if (intent == null) throw new IllegalStateException("支付意图认领后不存在: " + payNo);
    requireLegacyRoute(intent);
    return processClaimed(intent, claimToken);
  }

  /** 已存模式不受当前开关影响，未知协议和孤立意图不得进入旧资金调用。 */
  private void requireLegacyRoute(PayDebitIntentPO intent) {
    PayDebitModes.requireSupported(intent.debitMode);
    List<PayRecordPO> records = payRecordMapper.selectByPayNo(intent.payNo);
    if (records.size() != 1) throw PayDebitModes.unavailable();
    PayDebitModes.requireSupported(records.get(0).debitMode);
  }

  /** 先核对用户账本，再按订单确定成功、等待或唯一退款。 */
  private String processClaimed(PayDebitIntentPO intent, String claimToken) {
    String retryStatus = "INIT";
    try {
      // 1. 用户账本是扣款事实；404 表示该操作键尚未落账。
      UserClient.BalanceOperationView operation = readDebitOperation(intent);
      if (operation != null) {
        validateDebitOperation(intent, operation);
        if ("REJECTED".equals(operation.state())) {
          OrderClient.OrderView rejectedOrder = readOrderState(intent);
          if ("PAID".equals(rejectedOrder.status()) || "COMPLETED".equals(rejectedOrder.status()))
            throw new IllegalStateException("订单已支付但用户余额扣款被拒绝");
          return failWithoutDebit(intent, claimToken);
        }
        if (!"APPLIED".equals(operation.state()))
          throw new IllegalStateException("用户余额操作尚无最终结果: " + operation.state());
        retryStatus = "ORDER_SYNC_UNKNOWN";
      }

      // 2. 下单支付状态未知时不得扣款或退款；明确取消且未扣款直接失败。
      OrderClient.OrderView order = readOrderState(intent);
      if (operation != null && operation.refundOperationKey() != null) {
        if ("CANCELLED".equals(order.status()) && stockActionSucceeded(order)) {
          confirmRefundLedger(intent, operation.refundOperationKey());
          return finishRefunded(intent, claimToken);
        }
        throw new IllegalStateException("余额已退款但订单不是已取消状态: " + order.status());
      }
      if (operation == null && "CANCELLED".equals(order.status()))
        return failWithoutDebit(intent, claimToken);
      if (operation == null) {
        ApiResponse<UserClient.BalanceView> debit =
            userClient.debit(
                intent.userId,
                intent.userId,
                new UserClient.DebitRequest(
                    intent.payNo, intent.payNo, intent.orderNo, intent.amount));
        if (debit == null
            || !"0".equals(debit.code)
            || debit.data == null
            || !"APPLIED".equals(debit.data.state())) throw new IllegalStateException("用户余额扣款未确认");
        retryStatus = "ORDER_SYNC_UNKNOWN";
      }

      // 3. 扣款后先持久化恢复状态，再触发远程订单支付迁移。
      payRecordMapper.markOrderUnknown(intent.payNo, now());
      payDebitIntentMapper.updateOwnedStatus(intent.payNo, claimToken, "DEBITED", now());
      if ("CANCELLED".equals(order.status()) && stockActionSucceeded(order))
        return refundCancelled(intent, claimToken);
      if (("PAID".equals(order.status()) || "COMPLETED".equals(order.status()))
          && stockActionSucceeded(order)) return finishSuccess(intent, claimToken);
      if ("CANCELLED".equals(order.status())
          || "PAID".equals(order.status())
          || "COMPLETED".equals(order.status()))
        throw new IllegalStateException("订单库存动作尚未完成: " + order.stockActionStatus());
      if (!"PENDING_PAYMENT".equals(order.status()))
        throw new IllegalStateException("订单状态暂不可判定支付结果: " + order.status());

      ApiResponse<OrderClient.OrderView> paid = orderClient.paid(intent.orderNo, intent.userId);
      if (paid != null
          && "0".equals(paid.code)
          && paid.data != null
          && ("PAID".equals(paid.data.status()) || "COMPLETED".equals(paid.data.status())))
        return resolveAfterUncertainOrderWrite(intent, claimToken);
      // 4. 非明确成功时重新读订单；只有明确取消才退款。
      return resolveAfterUncertainOrderWrite(intent, claimToken);
    } catch (RuntimeException remoteOrPersistenceFailure) {
      // 5. 任何查询或远程结果未知都保留原 payNo 并定时重试，禁止猜测退款。
      if ("DEBITED".equals(intent.status) || "ORDER_SYNC_UNKNOWN".equals(intent.status))
        retryStatus = "ORDER_SYNC_UNKNOWN";
      if ("REFUND_PENDING".equals(intent.status)) retryStatus = "REFUND_PENDING";
      PayDebitIntentPO durableState = payDebitIntentMapper.findByPayNo(intent.payNo);
      if (durableState != null && "REFUND_PENDING".equals(durableState.status))
        retryStatus = "REFUND_PENDING";
      if ("ORDER_SYNC_UNKNOWN".equals(retryStatus))
        payRecordMapper.markOrderUnknown(intent.payNo, now());
      payDebitIntentMapper.recordRetry(
          intent.payNo,
          claimToken,
          retryStatus,
          remoteOrPersistenceFailure.toString(),
          now().plusSeconds(retryDelaySeconds(intent.attempts)),
          now());
      log.warn("支付结果待恢复，payNo={}，status={}", intent.payNo, retryStatus, remoteOrPersistenceFailure);
      return retryStatus;
    }
  }

  /** 远程支付写入未确认时，只依据新的订单事实决定后续动作。 */
  private String resolveAfterUncertainOrderWrite(PayDebitIntentPO intent, String claimToken) {
    OrderClient.OrderView current = readOrderState(intent);
    if (("PAID".equals(current.status()) || "COMPLETED".equals(current.status()))
        && stockActionSucceeded(current)) return finishSuccess(intent, claimToken);
    if ("CANCELLED".equals(current.status()) && stockActionSucceeded(current))
      return refundCancelled(intent, claimToken);
    throw new IllegalStateException("订单支付写入尚未确认: " + current.status());
  }

  /** 只有库存最终动作成功时才结清本地支付或退款记录。 */
  private static boolean stockActionSucceeded(OrderClient.OrderView order) {
    return "SUCCEEDED".equals(order.stockActionStatus());
  }

  /** 读取余额扣款操作，404 仅表示尚无该操作键的账本记录。 */
  private UserClient.BalanceOperationView readDebitOperation(PayDebitIntentPO intent) {
    try {
      ApiResponse<UserClient.BalanceOperationView> response =
          userClient.operation(intent.userId, intent.payNo, intent.userId);
      if (response == null || !"0".equals(response.code) || response.data == null)
        throw new IllegalStateException("用户账本查询结果未知");
      return response.data;
    } catch (FeignException missingOrUnavailable) {
      if (missingOrUnavailable.status() == 404) return null;
      throw missingOrUnavailable;
    }
  }

  /** 校验账本记录确实对应当前支付，不允许错账状态被当作成功。 */
  private void validateDebitOperation(
      PayDebitIntentPO intent, UserClient.BalanceOperationView operation) {
    if (!Objects.equals(operation.operationKey(), intent.payNo)
        || !"DEBIT".equals(operation.type())
        || new BigDecimal(operation.amount()).compareTo(intent.amount) != 0) {
      throw new BizException(ErrorCodes.CONFLICT, "余额账本与支付记录不一致", 409);
    }
  }

  /** 查询订单服务的只读支付状态，并检查归属和金额契约。 */
  private OrderClient.OrderView readOrderState(PayDebitIntentPO intent) {
    ApiResponse<OrderClient.OrderView> response =
        orderClient.paymentState(intent.orderNo, intent.userId);
    if (response == null || !"0".equals(response.code) || response.data == null)
      throw new IllegalStateException("订单支付状态查询结果未知");
    OrderClient.OrderView order = response.data;
    if (!Objects.equals(order.userId(), intent.userId)
        || order.payAmount().compareTo(intent.amount) != 0)
      throw new BizException(ErrorCodes.CONFLICT, "订单归属或金额与支付意图不一致", 409);
    return order;
  }

  /** 用户账本和订单都明确成功后才标记支付成功。 */
  private String finishSuccess(PayDebitIntentPO intent, String claimToken) {
    LocalDateTime terminalTime = now();
    requireOwnedTerminalWrite(
        payRecordMapper.markOwnedLegacySuccess(
            intent.payNo, claimToken, terminalTime, terminalTime));
    requireOwnedTerminalWrite(
        payDebitIntentMapper.finishOwnedLegacy(intent.payNo, claimToken, "SUCCESS", now()));
    return "SUCCESS";
  }

  /** 原扣款已退款时恢复支付记录终态，不再次增加余额。 */
  private String finishRefunded(PayDebitIntentPO intent, String claimToken) {
    payRecordMapper.markRefundPending(intent.payNo, now());
    LocalDateTime terminalTime = now();
    requireOwnedTerminalWrite(
        payRecordMapper.markOwnedLegacyRefunded(intent.payNo, claimToken, terminalTime));
    requireOwnedTerminalWrite(
        payDebitIntentMapper.finishOwnedLegacy(intent.payNo, claimToken, "REFUNDED", now()));
    return "REFUNDED";
  }

  /** 确认订单取消后先持久化退款意图，再调用幂等用户退款接口。 */
  private String refundCancelled(PayDebitIntentPO intent, String claimToken) {
    int changed = payRecordMapper.markRefundPending(intent.payNo, now());
    requirePayRecordStatus(intent.payNo, "REFUND_PENDING", changed);
    payDebitIntentMapper.updateOwnedStatus(intent.payNo, claimToken, "REFUND_PENDING", now());
    ApiResponse<UserClient.BalanceView> refund =
        userClient.refund(
            intent.userId,
            intent.userId,
            new UserClient.RefundRequest(
                "refund:" + intent.payNo,
                intent.payNo,
                intent.payNo,
                intent.orderNo,
                intent.amount));
    if (refund == null
        || !"0".equals(refund.code)
        || refund.data == null
        || !Objects.equals("refund:" + intent.payNo, refund.data.operationKey())
        || !"APPLIED".equals(refund.data.state())) throw new IllegalStateException("用户余额退款未确认");
    confirmRefundLedger(intent, "refund:" + intent.payNo);
    return finishRefunded(intent, claimToken);
  }

  /**
   * 退款终态前复用User查询证明单笔完整金额；404和缺证沿原恢复链重试，不转换为无扣款。
   * 只核Pay可见字段，不把POST响应、marker或部分金额当作全额退款，也不推断User完整资金来源。
   */
  private void confirmRefundLedger(PayDebitIntentPO intent, String refundKey) {
    if (refundKey == null || refundKey.isBlank()) {
      throw new IllegalStateException("退款账本操作键缺失");
    }
    ApiResponse<UserClient.BalanceOperationView> response =
        userClient.operation(intent.userId, refundKey, intent.userId);
    if (response == null || !"0".equals(response.code) || response.data == null) {
      throw new IllegalStateException("退款账本查询结果未知");
    }
    UserClient.BalanceOperationView refund = response.data;
    if (!Objects.equals(refundKey, refund.operationKey())
        || !"REFUND".equals(refund.type())
        || !"APPLIED".equals(refund.state())
        || refund.amount() == null) {
      throw new BizException(ErrorCodes.CONFLICT, "退款账本与支付记录不一致", 409);
    }
    BigDecimal refundAmount;
    try {
      refundAmount = new BigDecimal(refund.amount());
    } catch (NumberFormatException invalidAmount) {
      throw new BizException(ErrorCodes.CONFLICT, "退款账本金额不合法", 409);
    }
    if (refundAmount.signum() <= 0
        || refundAmount.stripTrailingZeros().scale() > 2
        || refundAmount.compareTo(MAX_REFUND_MONEY) > 0
        || refundAmount.compareTo(intent.amount) != 0) {
      throw new BizException(ErrorCodes.CONFLICT, "退款账本与支付记录不一致", 409);
    }
  }

  /** 明确没有扣款且订单已取消或账本拒绝时标记失败。 */
  private String failWithoutDebit(PayDebitIntentPO intent, String claimToken) {
    LocalDateTime terminalTime = now();
    requireOwnedTerminalWrite(
        payRecordMapper.markOwnedLegacyFailed(intent.payNo, claimToken, terminalTime));
    requireOwnedTerminalWrite(
        payDebitIntentMapper.finishOwnedLegacy(intent.payNo, claimToken, "FAILED", now()));
    return "FAILED";
  }

  /** 终态必须恰好更新一行，不以同终态readback绕过owner或期限；异常沿原恢复链处理。 */
  private static void requireOwnedTerminalWrite(int changedRows) {
    if (changedRows != 1) throw new IllegalStateException("支付终态写入未满足当前租约");
  }

  /** CAS 未更新时只接受已经达到同一目标状态，拒绝覆盖其他支付终态。 */
  private void requirePayRecordStatus(String payNo, String targetStatus, int changed) {
    if (changed == 1) return;
    List<PayRecordPO> records = payRecordMapper.selectByPayNo(payNo);
    if (records.size() == 1 && targetStatus.equals(records.get(0).status)) return;
    throw new BizException(ErrorCodes.CONFLICT, "支付状态已被其他操作更新", 409);
  }

  /** 对重复故障执行有上限的简单退避，记录仍保留供持续对账。 */
  private static long retryDelaySeconds(Integer attempts) {
    int completedAttempts = attempts == null ? 1 : attempts;
    return Math.min(60, 5L * Math.max(1, completedAttempts));
  }

  /** 获取 Asia/Shanghai 语义的本地数据库时间。 */
  private static LocalDateTime now() {
    return LocalDateTime.now(BUSINESS_ZONE);
  }
}
