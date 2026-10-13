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

  /** 仅包正向或退款的本地中间阶段两写；独立于终态，不包含RPC或重试清理。 */
  private final TransactionTemplate stageTransactions;

  /** 仅包异常后的本地重试清理，与阶段和终态分别提交，不包含RPC。 */
  private final TransactionTemplate retryTransactions;

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
    this.stageTransactions = new TransactionTemplate(transactionManager);
    this.stageTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.retryTransactions = new TransactionTemplate(transactionManager);
    this.retryTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
    PayRecordPO payment = requireLegacyRoute(payNo, intent);
    return processClaimed(intent, claimToken, payment);
  }

  /** 恢复入口必须独立于caller事务；拒绝发生在catch/retry外，保留原调用者上下文。 */
  private void requireStandaloneLegacyRecovery() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || RootContext.getXID() != null) {
      throw new IllegalStateException("Legacy支付恢复不能继承事务上下文");
    }
  }

  /**
   * 保留协议错误优先级，再核原请求键与两表载荷；查询后漂移不属于本校验的保证。
   *
   * @return 本次已校验的原支付记录，不再额外读取或改写状态
   */
  private PayRecordPO requireLegacyRoute(String requestedPayNo, PayDebitIntentPO intent) {
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
    return payment;
  }

  /** 正金额须无损落在DECIMAL(18,2)，允许数值相等的额外尾零。 */
  private static boolean validRecoveryAmount(BigDecimal amount) {
    return amount != null
        && amount.signum() > 0
        && amount.stripTrailingZeros().scale() <= 2
        && amount.compareTo(MAX_LEGACY_AMOUNT) <= 0;
  }

  /** 先核对用户账本，再按订单确定成功、等待或唯一退款。 */
  private String processClaimed(PayDebitIntentPO intent, String claimToken, PayRecordPO payment) {
    String observedPaymentStatus = payment.status;
    String retryStatus =
        "REFUND_PENDING".equals(observedPaymentStatus)
            ? "REFUND_PENDING"
            : "ORDER_SYNC_UNKNOWN".equals(observedPaymentStatus) ? "ORDER_SYNC_UNKNOWN" : "INIT";
    try {
      // 1. 用户账本是扣款事实；404 只表示当前未观察到，不能抹去已记录的恢复阶段。
      UserClient.BalanceOperationView operation = readDebitOperation(intent);
      if (operation != null) {
        validateDebitOperation(intent, operation);
        if ("REJECTED".equals(operation.state())) {
          OrderClient.OrderView rejectedOrder = readOrderState(intent);
          if ("PAID".equals(rejectedOrder.status()) || "COMPLETED".equals(rejectedOrder.status()))
            throw new IllegalStateException("订单已支付但用户余额扣款被拒绝");
          if (!"INIT".equals(intent.status)
              || !"PENDING".equals(observedPaymentStatus)
              || operation.refundOperationKey() != null)
            throw new IllegalStateException("扣款拒绝事实与资金恢复状态不一致");
          return failWithoutDebit(intent, claimToken, true);
        }
        if (!"APPLIED".equals(operation.state()))
          throw new IllegalStateException("用户余额操作尚无最终结果: " + operation.state());
        retryStatus = "ORDER_SYNC_UNKNOWN";
      }

      // 2. 下单支付状态未知时不得扣款或退款；明确取消且未扣款直接失败。
      OrderClient.OrderView order = readOrderState(intent);
      // 已记录退款方向不能回到订单支付，须先证明取消和库存回滚完成。
      if (operation != null
          && ("REFUND_PENDING".equals(intent.status)
              || "REFUND_PENDING".equals(observedPaymentStatus))
          && !("CANCELLED".equals(order.status()) && stockActionSucceeded(order)))
        throw new IllegalStateException("退款恢复阶段缺少订单取消或库存回滚证据");
      if (operation != null && operation.refundOperationKey() != null) {
        if ("CANCELLED".equals(order.status()) && stockActionSucceeded(order)) {
          confirmRefundLedger(intent, operation.refundOperationKey());
          return finishRefunded(intent, claimToken);
        }
        throw new IllegalStateException("余额已退款但订单不是已取消状态: " + order.status());
      }
      if (operation == null && !"INIT".equals(intent.status))
        throw new IllegalStateException("已有资金恢复状态但原扣款账本缺失");
      if (operation == null && !"PENDING".equals(observedPaymentStatus))
        throw new IllegalStateException("原支付记录已进入资金恢复阶段但扣款账本缺失");
      if (operation == null && "CANCELLED".equals(order.status()))
        return failWithoutDebit(intent, claimToken, false);
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
      commitOwnedLegacyStage(intent, claimToken, "DEBITED");
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
      if ("REFUND_PENDING".equals(intent.status) || "REFUND_PENDING".equals(observedPaymentStatus))
        retryStatus = "REFUND_PENDING";
      PayDebitIntentPO durableState = payDebitIntentMapper.findByPayNo(intent.payNo);
      if (durableState != null && "REFUND_PENDING".equals(durableState.status))
        retryStatus = "REFUND_PENDING";
      recordOwnedLegacyRetry(
          intent, claimToken, retryStatus, remoteOrPersistenceFailure.toString());
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
    // 1. 先保留原扣款复核优先级，再在本地事务外复核当前订单和库存确认。
    requireLatestDebitProof(intent);
    OrderClient.OrderView current = readOrderState(intent);
    if (!("PAID".equals(current.status()) || "COMPLETED".equals(current.status()))
        || !stockActionSucceeded(current)) throw new IllegalStateException("订单支付与库存确认证据未证明支付成功");
    // 2. 两端本次观察均有成功证据后，进入原本地双表终态事务。
    commitOwnedLegacyTerminal(intent, claimToken, "SUCCESS");
    return "SUCCESS";
  }

  /** 在本地终态事务外复核原扣款；只证明本次观察，不保证读取后的来源稳定。 */
  private void requireLatestDebitProof(PayDebitIntentPO intent) {
    UserClient.BalanceOperationView latest = readDebitOperation(intent);
    if (latest == null) throw new IllegalStateException("原扣款账本未证明支付成功");
    validateDebitOperation(intent, latest);
    if (!"APPLIED".equals(latest.state()) || latest.refundOperationKey() != null)
      throw new IllegalStateException("原扣款账本未证明支付成功");
  }

  /** 原扣款已退款时复核当前取消/库存证据，再恢复支付终态，不再次增加余额。 */
  private String finishRefunded(PayDebitIntentPO intent, String claimToken) {
    OrderClient.OrderView current = readOrderState(intent);
    if (!"CANCELLED".equals(current.status()) || !stockActionSucceeded(current))
      throw new IllegalStateException("订单取消与库存回滚证据未证明退款完成");
    commitOwnedLegacyTerminal(intent, claimToken, "REFUNDED");
    return "REFUNDED";
  }

  /** 确认订单取消后先持久化退款意图，再调用幂等用户退款接口。 */
  private String refundCancelled(PayDebitIntentPO intent, String claimToken) {
    commitOwnedLegacyStage(intent, claimToken, "REFUND_PENDING");
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

  /**
   * 在终态事务外复核原无扣款或拒绝来源，再按原短事务标记失败。
   *
   * @param expectedRejected 原流程已观察拒绝来源；false表示已取消订单的账本缺失来源
   */
  private String failWithoutDebit(
      PayDebitIntentPO intent, String claimToken, boolean expectedRejected) {
    // 1. 末次读取只证明本次观察，不能用早期缺失或拒绝覆盖已变化的资金来源。
    UserClient.BalanceOperationView latest = readDebitOperation(intent);
    if (expectedRejected) {
      if (latest == null) throw new IllegalStateException("原扣款账本未证明支付失败");
      validateDebitOperation(intent, latest);
      if (!"REJECTED".equals(latest.state()) || latest.refundOperationKey() != null)
        throw new IllegalStateException("原扣款账本未证明支付失败");
    } else if (latest != null) throw new IllegalStateException("原扣款账本未证明支付失败");
    // 2. 证明通过后仍使用原双表终态事务，保留owner/lease条件及整体回滚。
    commitOwnedLegacyTerminal(intent, claimToken, "FAILED");
    return "FAILED";
  }

  /** 重试清理独立提交；意图零行回滚并保原等待方向，数据库异常回滚后原样传播。 */
  private void recordOwnedLegacyRetry(
      PayDebitIntentPO intent, String claimToken, String retryStatus, String error) {
    // 1. 不把异常后的清理写入caller全局事务，也不解绑caller的上下文。
    if (RootContext.getXID() != null) return;
    retryTransactions.executeWithoutResult(
        transactionStatus -> {
          // 2. 先核并锁定两表原载荷；缺证只跳过清理，不改写新tuple或释放其租约。
          if (payRecordMapper.lockOwnedLegacyRetrySource(intent, claimToken, now()) == null) return;
          // 3. 保原支付清理顺序与零行best-effort语义，不调用无owner旧方法。
          if ("ORDER_SYNC_UNKNOWN".equals(retryStatus))
            payRecordMapper.markOwnedLegacyOrderUnknown(intent.payNo, claimToken, now());
          // 4. 意图重试未落盘则撤回本次支付清理；原错误截断、退避和清租约由Mapper保持。
          if (payDebitIntentMapper.recordRetryOwnedLegacy(
                  intent.payNo,
                  claimToken,
                  retryStatus,
                  error,
                  now().plusSeconds(retryDelaySeconds(intent.attempts)),
                  now())
              != 1) transactionStatus.setRollbackOnly();
        });
  }

  /** 中间阶段两写独立原子提交；任何零行或异常回滚该阶段，不包远端调用或后续终态。 */
  private void commitOwnedLegacyStage(PayDebitIntentPO intent, String claimToken, String stage) {
    if (RootContext.getXID() != null) throw new IllegalStateException("Legacy阶段不得继承全局事务");
    stageTransactions.executeWithoutResult(
        transactionStatus -> {
          // 1. 先核并锁定两表原载荷，防止旧远端证据推进已变化的本地支付。
          if (payRecordMapper.lockOwnedLegacyStageSource(intent, claimToken, now()) == null)
            throw new IllegalStateException("支付阶段原载荷或领取资格已变化");
          // 2. 按原方向写支付记录，每次仍校验当前owner和未过期租约。
          int changed =
              switch (stage) {
                case "DEBITED" ->
                    payRecordMapper.markOwnedLegacyOrderUnknown(intent.payNo, claimToken, now());
                case "REFUND_PENDING" ->
                    payRecordMapper.markOwnedLegacyRefundPending(intent.payNo, claimToken, now());
                default -> throw new IllegalArgumentException("未知Legacy支付阶段");
              };
          requireOwnedLegacyWrite(changed);
          // 3. 同连接推进意图，任一写未满足条件时整体回滚，异常交回原重试链。
          requireOwnedLegacyWrite(
              payDebitIntentMapper.updateOwnedLegacyStatus(intent.payNo, claimToken, stage, now()));
        });
  }

  /** 同一Pay本地事务提交终态及退款收尾准备；任一缺证或异常均回滚，不继承全局上下文。 */
  private void commitOwnedLegacyTerminal(
      PayDebitIntentPO intent, String claimToken, String terminalState) {
    if (RootContext.getXID() != null) throw new IllegalStateException("Legacy终态不得继承全局事务");
    terminalTransactions.executeWithoutResult(
        transactionStatus -> {
          LocalDateTime terminalTime = now();
          // 1. 锁定两表原载荷，避免较早远端证明被用于已变化的本地支付。
          if (payRecordMapper.lockOwnedLegacyTerminalSource(intent, claimToken, terminalTime)
              == null) throw new IllegalStateException("支付终态原载荷或领取资格已变化");
          // 2. 退款收尾准备也在原载荷锁内，失败不能留下事务外的REFUND_PENDING更新。
          if ("REFUNDED".equals(terminalState)) {
            requireOwnedLegacyWrite(
                payRecordMapper.markOwnedLegacyRefundPending(intent.payNo, claimToken, now()));
            terminalTime = now();
          }
          // 3. 同一事务内执行原终态两写，任一写不满足资格则整体回滚。
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
