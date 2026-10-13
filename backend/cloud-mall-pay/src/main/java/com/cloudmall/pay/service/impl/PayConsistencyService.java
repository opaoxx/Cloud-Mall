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
import io.seata.core.context.RootContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 用持久支付意图、用户账本和订单事实收敛模拟余额支付。 */
@Service
public class PayConsistencyService {
  /** 支付结果未知和恢复重试日志。 */
  private static final Logger log = LoggerFactory.getLogger(PayConsistencyService.class);

  /** 与数据库 DATETIME(3) 语义一致的业务时区。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** Legacy单笔支付/退款金额必须无损落在数据库DECIMAL(18,2)容量内。 */
  private static final BigDecimal MAX_LEGACY_AMOUNT = new BigDecimal("9999999999999999.99");

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

  /** 仅包本地Pay两表终态写的独立短事务，不包含RPC或重试。 */
  private final TransactionTemplate terminalTransactions;

  /** 仅代表本进程扫描进度；重启复位，不等于业务成功或持久完成。 */
  private final AtomicReference<Long> scanAfterId = new AtomicReference<>();

  /** 创建支付一致性服务。 */
  public PayConsistencyService(
      PayDebitIntentService payDebitIntentService,
      PayDebitIntentMapper payDebitIntentMapper,
      PayRecordMapper payRecordMapper,
      UserClient userClient,
      OrderClient orderClient,
      PlatformTransactionManager transactionManager) {
    this.payDebitIntentService = payDebitIntentService;
    this.payDebitIntentMapper = payDebitIntentMapper;
    this.payRecordMapper = payRecordMapper;
    this.userClient = userClient;
    this.orderClient = orderClient;
    this.terminalTransactions =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "Pay终态缺少本地事务管理器"));
    this.terminalTransactions.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** 在无调用者事务上下文时保存意图并尝试支付同步；已有事务则在保存前拒绝。 */
  public String initiate(PayVO payment) {
    // 1. 先拒绝继承的事务上下文，避免将恢复写和RPC带入调用者事务。
    requireStandaloneLegacyRecovery();
    // 2. 独立提交 INIT，崩溃后恢复器仍能发现同一 payNo。
    PayDebitIntentPO intent = payDebitIntentService.ensureIntent(payment);
    // 3. 按数据库租约处理一次；其他实例处理时返回其当前可见状态。
    return process(intent.payNo);
  }

  /** 无调用者事务时每轮最多恢复50个到期候选，以主键轮转避免坏队头阻塞。 */
  @Scheduled(fixedDelayString = "${cloudmall.pay.recovery-interval-ms:5000}")
  public void recoverDue() {
    // 1. 扫描前拒绝继承的事务，保持每条恢复的独立提交边界。
    requireStandaloneLegacyRecovery();
    Long startCursor = scanAfterId.get();
    LocalDateTime scanTime = now();
    // 2. 查询异常不改变游标；首段为空才回环，最多两次查询和50条处理。
    List<PayDebitIntentPO> due = payDebitIntentMapper.findDueAfter(scanTime, startCursor, 50);
    if (due.isEmpty() && startCursor != null) {
      due = payDebitIntentMapper.findDueAfter(scanTime, null, 50);
    }
    Long endCursor = due.isEmpty() ? null : due.get(due.size() - 1).id;
    if (!due.isEmpty() && endCursor == null) endCursor = startCursor;
    try {
      // 3. 处理失败也前移扫描，坏行仅观察，不修复或猜测其业务状态。
      for (PayDebitIntentPO intent : due) {
        try {
          process(intent.payNo);
        } catch (RuntimeException recoveryFailure) {
          log.warn("支付恢复任务本轮失败，下一轮继续处理，payNo={}", intent.payNo, recoveryFailure);
        }
      }
    } finally {
      // 4. 只有原游标仍有效才更新，迟到旧轮不能覆盖已变化的扫描进度。
      scanAfterId.compareAndSet(startCursor, endCursor);
    }
  }

  /** 无调用者事务时读取并恢复支付意图；已有事务则在读取或领取前拒绝。 */
  public String process(String payNo) {
    // 1. 读取和领取前拒绝外部事务，不终止或解绑调用者上下文。
    requireStandaloneLegacyRecovery();
    // 2. 原意图与支付单模式先核对，再领取租约；拒绝路径不修改任何恢复字段。
    PayDebitIntentPO pending = payDebitIntentMapper.findByPayNo(payNo);
    if (pending != null) requireLegacyRoute(payNo, pending);
    LocalDateTime currentTime = now();
    // 3. 每次领取使用独立身份，同实例迟到旧执行不得匹配重领后的owner。
    String claimToken = UUID.randomUUID().toString();
    if (payDebitIntentMapper.claim(payNo, claimToken, currentTime.plusSeconds(30), currentTime)
        != 1) {
      PayDebitIntentPO existing = payDebitIntentMapper.findByPayNo(payNo);
      if (existing != null) requireLegacyRoute(payNo, existing);
      return existing == null ? "INIT" : existing.status;
    }
    // 4. 领取成功后仅以本次身份进入处理，后续条件写不能复用实例标识。
    PayDebitIntentPO intent = payDebitIntentMapper.findByPayNo(payNo);
    if (intent == null) throw new IllegalStateException("支付意图认领后不存在: " + payNo);
    requireLegacyRoute(payNo, intent);
    return processClaimed(intent, claimToken);
  }

  /** 恢复入口必须独立于caller事务；拒绝发生在catch/retry外，保留原调用者上下文。 */
  private void requireStandaloneLegacyRecovery() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || RootContext.getXID() != null) {
      throw new IllegalStateException("Legacy支付恢复不能继承事务上下文");
    }
  }

  /** 保留协议错误优先级，再核原请求键与两表载荷；查询后漂移不属于本校验的保证。 */
  private void requireLegacyRoute(String requestedPayNo, PayDebitIntentPO intent) {
    // 1. 原模式先核对，不用当前开关改写协议或把模式错误转换成载荷冲突。
    PayDebitModes.requireSupported(intent.debitMode);
    List<PayRecordPO> records = payRecordMapper.selectByPayNo(requestedPayNo);
    if (records.size() != 1) throw PayDebitModes.unavailable();
    PayRecordPO payment = records.get(0);
    PayDebitModes.requireSupported(payment.debitMode);
    // 2. 请求键和原订单/用户必须精确相等，两表共同非法也不能作为恢复来源。
    if (requestedPayNo == null
        || requestedPayNo.isBlank()
        || !Objects.equals(requestedPayNo, intent.payNo)
        || !Objects.equals(requestedPayNo, payment.payNo)
        || intent.orderNo == null
        || intent.orderNo.isBlank()
        || !Objects.equals(intent.orderNo, payment.orderNo)
        || intent.userId == null
        || intent.userId <= 0
        || !Objects.equals(intent.userId, payment.userId)
        || !validRecoveryAmount(intent.amount)
        || !validRecoveryAmount(payment.amount)
        || intent.amount.compareTo(payment.amount) != 0) {
      throw new BizException(ErrorCodes.CONFLICT, "支付意图与原支付记录不一致", 409);
    }
  }

  /** 正金额须无损落在DECIMAL(18,2)，允许数值相等的额外尾零。 */
  private static boolean validRecoveryAmount(BigDecimal amount) {
    return amount != null
        && amount.signum() > 0
        && amount.stripTrailingZeros().scale() <= 2
        && amount.compareTo(MAX_LEGACY_AMOUNT) <= 0;
  }

  /** 先核对用户账本，再按订单确定成功、等待或唯一退款。 */
  private String processClaimed(PayDebitIntentPO intent, String claimToken) {
    String retryStatus = "INIT";
    try {
      // 1. 用户账本是扣款事实；404 只表示当前未观察到，不能抹去已记录的恢复阶段。
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
      if (operation == null && !"INIT".equals(intent.status))
        throw new IllegalStateException("已有资金恢复状态但原扣款账本缺失");
      if (operation == null && "CANCELLED".equals(order.status()))
        return failWithoutDebit(intent, claimToken);
      if (operation == null) {
        // 已支付订单缺少本支付的账本时只能核对来源，不能追加一笔新扣款。
        if (!"PENDING_PAYMENT".equals(order.status()))
          throw new IllegalStateException("订单已支付但未观察到原扣款事实");
        requireOwnedLegacyLease(intent.payNo, claimToken);
        ApiResponse<UserClient.BalanceView> debit =
            userClient.debit(
                intent.userId,
                intent.userId,
                new UserClient.DebitRequest(
                    intent.payNo, intent.payNo, intent.orderNo, intent.amount));
        if (debit == null
            || !"0".equals(debit.code)
            || debit.data == null
            || !"APPLIED".equals(debit.data.state())
            || !Objects.equals(intent.payNo, debit.data.operationKey()))
          throw new IllegalStateException("用户余额扣款未确认");
        retryStatus = "ORDER_SYNC_UNKNOWN";
      }

      // 3. 已扣款且明确取消时直接恢复退款，不能先将REFUND_PENDING回写为订单等待。
      if ("CANCELLED".equals(order.status()) && stockActionSucceeded(order))
        return refundCancelled(intent, claimToken);
      // 4. 其它扣款路径先持久化恢复状态，再触发远程订单支付迁移。
      requireOwnedLegacyWrite(
          payRecordMapper.markOwnedLegacyOrderUnknown(intent.payNo, claimToken, now()));
      requireOwnedLegacyWrite(
          payDebitIntentMapper.updateOwnedLegacyStatus(intent.payNo, claimToken, "DEBITED", now()));
      if (("PAID".equals(order.status()) || "COMPLETED".equals(order.status()))
          && stockActionSucceeded(order)) return finishSuccess(intent, claimToken);
      if ("CANCELLED".equals(order.status())
          || "PAID".equals(order.status())
          || "COMPLETED".equals(order.status()))
        throw new IllegalStateException("订单库存动作尚未完成: " + order.stockActionStatus());
      if (!"PENDING_PAYMENT".equals(order.status()))
        throw new IllegalStateException("订单状态暂不可判定支付结果: " + order.status());

      requireOwnedLegacyLease(intent.payNo, claimToken);
      ApiResponse<OrderClient.OrderView> paid = orderClient.paid(intent.orderNo, intent.userId);
      if (paid != null
          && "0".equals(paid.code)
          && paid.data != null
          && ("PAID".equals(paid.data.status()) || "COMPLETED".equals(paid.data.status())))
        return resolveAfterUncertainOrderWrite(intent, claimToken);
      // 5. 非明确成功时重新读订单；只有明确取消才退款。
      return resolveAfterUncertainOrderWrite(intent, claimToken);
    } catch (RuntimeException remoteOrPersistenceFailure) {
      // 6. 任何查询或远程结果未知都保留原 payNo 并定时重试，禁止猜测退款。
      if ("DEBITED".equals(intent.status) || "ORDER_SYNC_UNKNOWN".equals(intent.status))
        retryStatus = "ORDER_SYNC_UNKNOWN";
      if ("REFUND_PENDING".equals(intent.status)) retryStatus = "REFUND_PENDING";
      PayDebitIntentPO durableState = payDebitIntentMapper.findByPayNo(intent.payNo);
      if (durableState != null && "REFUND_PENDING".equals(durableState.status))
        retryStatus = "REFUND_PENDING";
      if ("ORDER_SYNC_UNKNOWN".equals(retryStatus))
        payRecordMapper.markOwnedLegacyOrderUnknown(intent.payNo, claimToken, now());
      payDebitIntentMapper.recordRetryOwnedLegacy(
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

  /** 只读订单须精确对应原身份和金额，且状态可判定，才参与资金恢复决策。 */
  private OrderClient.OrderView readOrderState(PayDebitIntentPO intent) {
    ApiResponse<OrderClient.OrderView> response =
        orderClient.paymentState(intent.orderNo, intent.userId);
    if (response == null || !"0".equals(response.code) || response.data == null)
      throw new IllegalStateException("订单支付状态查询结果未知");
    OrderClient.OrderView order = response.data;
    if (!Objects.equals(order.orderNo(), intent.orderNo)
        || !Objects.equals(order.userId(), intent.userId)
        || order.payAmount().compareTo(intent.amount) != 0)
      throw new BizException(ErrorCodes.CONFLICT, "订单身份、归属或金额与支付意图不一致", 409);
    if (!("PENDING_PAYMENT".equals(order.status())
        || "PAID".equals(order.status())
        || "COMPLETED".equals(order.status())
        || "CANCELLED".equals(order.status()))) throw new IllegalStateException("订单支付状态暂不可判定");
    return order;
  }

  /** 用户账本和订单都明确成功后才标记支付成功。 */
  private String finishSuccess(PayDebitIntentPO intent, String claimToken) {
    commitOwnedLegacyTerminal(intent, claimToken, "SUCCESS");
    return "SUCCESS";
  }

  /** 原扣款已退款时恢复支付记录终态，不再次增加余额。 */
  private String finishRefunded(PayDebitIntentPO intent, String claimToken) {
    requireOwnedLegacyWrite(
        payRecordMapper.markOwnedLegacyRefundPending(intent.payNo, claimToken, now()));
    commitOwnedLegacyTerminal(intent, claimToken, "REFUNDED");
    return "REFUNDED";
  }

  /** 确认订单取消后先持久化退款意图，再调用幂等用户退款接口。 */
  private String refundCancelled(PayDebitIntentPO intent, String claimToken) {
    requireOwnedLegacyWrite(
        payRecordMapper.markOwnedLegacyRefundPending(intent.payNo, claimToken, now()));
    requireOwnedLegacyWrite(
        payDebitIntentMapper.updateOwnedLegacyStatus(
            intent.payNo, claimToken, "REFUND_PENDING", now()));
    requireOwnedLegacyLease(intent.payNo, claimToken);
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
        || refundAmount.compareTo(MAX_LEGACY_AMOUNT) > 0
        || refundAmount.compareTo(intent.amount) != 0) {
      throw new BizException(ErrorCodes.CONFLICT, "退款账本与支付记录不一致", 409);
    }
  }

  /** 明确没有扣款且订单已取消或账本拒绝时标记失败。 */
  private String failWithoutDebit(PayDebitIntentPO intent, String claimToken) {
    commitOwnedLegacyTerminal(intent, claimToken, "FAILED");
    return "FAILED";
  }

  /** 同一Pay本地事务内提交两个终态写；任一缺证或异常均回滚，不继承全局上下文。 */
  private void commitOwnedLegacyTerminal(
      PayDebitIntentPO intent, String claimToken, String terminalState) {
    if (RootContext.getXID() != null) throw new IllegalStateException("Legacy终态不得继承全局事务");
    terminalTransactions.executeWithoutResult(
        transactionStatus -> {
          LocalDateTime terminalTime = now();
          int changed =
              switch (terminalState) {
                case "SUCCESS" ->
                    payRecordMapper.markOwnedLegacySuccess(
                        intent.payNo, claimToken, terminalTime, terminalTime);
                case "REFUNDED" ->
                    payRecordMapper.markOwnedLegacyRefunded(intent.payNo, claimToken, terminalTime);
                case "FAILED" ->
                    payRecordMapper.markOwnedLegacyFailed(intent.payNo, claimToken, terminalTime);
                default -> throw new IllegalArgumentException("未知Legacy支付终态");
              };
          requireOwnedLegacyWrite(changed);
          requireOwnedLegacyWrite(
              payDebitIntentMapper.finishOwnedLegacy(
                  intent.payNo, claimToken, terminalState, now()));
        });
  }

  /** 正常推进必须恰好更新一行，不以readback绕过owner或期限；异常沿原恢复链处理。 */
  private static void requireOwnedLegacyWrite(int changedRows) {
    if (changedRows != 1) throw new IllegalStateException("支付终态写入未满足当前租约");
  }

  /** 写RPC前核当前资格；不承诺观察之后的源变化或远端执行原子性。 */
  private void requireOwnedLegacyLease(String payNo, String claimToken) {
    if (!payDebitIntentMapper.hasOwnedLegacyLease(payNo, claimToken, now()))
      throw new IllegalStateException("支付恢复领取资格已失效");
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
