package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.po.PayCallbackLogPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayCallbackLogMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PayProtocolMetrics.*;
import com.cloudmall.pay.util.PayDebitModes;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 公开支付回调的独立预绑定与完成记录服务，不写支付业务状态。 */
@Service
public class PayCallbackReceiptService {
  /** 正式容器强制注入真实观察组件，旧直接构造保持无资源兼容。 */
  @Autowired private PayProtocolMetrics metrics = PayProtocolMetrics.noop();

  /** 回调日志数据访问入口。 */
  private final PayCallbackLogMapper callbackLogs;

  /** 原支付记录锁与不可变载荷核验入口。 */
  private final PayRecordMapper payments;

  /** 回调载荷序列化和历史载荷解析器。 */
  private final ObjectMapper objectMapper;

  /** 创建公开回调记录服务。 */
  public PayCallbackReceiptService(
      PayCallbackLogMapper callbackLogs, PayRecordMapper payments, ObjectMapper objectMapper) {
    this.callbackLogs = callbackLogs;
    this.payments = payments;
    this.objectMapper = objectMapper;
  }

  /**
   * 独立预绑定一次回调，不代表支付已成功或失败。
   *
   * @param payment 当前支付视图
   * @param request 回调请求
   * @param callbackId 已分配的回调幂等标识
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void claim(PayVO payment, PayCallbackDTO request, String callbackId) {
    Mode observedMode = Mode.UNKNOWN;
    try {
      // 1. 先核验请求和调用方已读取的支付不可变字段。
      validateRequest(payment, request, callbackId);
      // 2. 在独立本地事务中锁定真实支付行，避免视图与数据库载荷分叉。
      PayRecordPO lockedPayment = lockPayment(request.payNo);
      observedMode = PayProtocolMetrics.mode(lockedPayment.debitMode);
      validatePayment(lockedPayment, payment, request);
      // 3. 预绑定完整事实后重新锁定日志，兼容并发插入和历史空 payload。
      PayCallbackLogPO candidate = receivedLog(payment, request, callbackId);
      int inserted = callbackLogs.insertReceived(candidate);
      PayCallbackLogPO bound = callbackLogs.lockedSelect(callbackId);
      validateBound(bound, payment, request, callbackId);

      metrics.claim(
          observedMode,
          request.success ? Direction.SUCCESS : Direction.FAILURE,
          inserted == 1
              ? ClaimResult.NEW
              : inserted == 0 ? ClaimResult.REPLAY : ClaimResult.UNKNOWN);
    } catch (RuntimeException failure) {
      if (request != null)
        metrics.claim(
            observedMode,
            request.success ? Direction.SUCCESS : Direction.FAILURE,
            PayProtocolMetrics.failureOutcome(failure) == Outcome.CONFLICT
                ? ClaimResult.CONFLICT
                : ClaimResult.UNKNOWN);
      throw failure;
    }
  }

  /**
   * 独立完成一次已经由业务编排确认的回调事实。
   *
   * @param payment 当前支付视图
   * @param request 回调请求
   * @param callbackId 已分配的回调幂等标识
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void complete(PayVO payment, PayCallbackDTO request, String callbackId) {
    // 1. 重新锁定并核验原支付载荷，不信任 claim 之外的瞬时对象。
    validateRequest(payment, request, callbackId);
    PayRecordPO lockedPayment = lockPayment(request.payNo);
    validatePayment(lockedPayment, payment, request);
    // 2. 绑定记录必须同支付、同方向且 payload 可验证，才允许写终态。
    PayCallbackLogPO bound = callbackLogs.lockedSelect(callbackId);
    completeLocked(lockedPayment, bound, payment, request, callbackId);
  }

  /**
   * 在独立事务中恢复已有 Legacy 支付终态对应的完整回调日志，不重复业务副作用。
   *
   * @param callbackId 已持久预绑定的回调标识
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recoverReceived(String callbackId) {
    // 1. 只读定位原支付，候选本身不能授权完成回调。
    if (callbackId == null || callbackId.isBlank() || callbackId.length() > 128)
      throw conflict("回调标识不完整");
    List<PayCallbackLogPO> observed = callbackLogs.selectByCallbackId(callbackId);
    if (observed.size() != 1) throw conflict("回调记录不存在或不唯一");
    PayCallbackLogPO snapshot = observed.get(0);
    if (snapshot == null
        || snapshot.id == null
        || !Objects.equals(snapshot.callbackId, callbackId)
        || snapshot.payNo == null
        || snapshot.payNo.isBlank()) throw conflict("回调原始身份不完整");
    // 2. 沿原 payment → callback 锁序重读，拒绝候选之后的删除重建或载荷分叉。
    PayRecordPO lockedPayment = lockPayment(snapshot.payNo);
    PayCallbackLogPO bound = callbackLogs.lockedSelect(callbackId);
    if (bound == null
        || !Objects.equals(bound.id, snapshot.id)
        || !Objects.equals(bound.callbackId, callbackId)
        || !Objects.equals(bound.payNo, snapshot.payNo)) throw conflict("回调原始身份发生变化");
    if (!PayDebitModes.LEGACY.equals(lockedPayment.debitMode)) return;
    // 3. 只消费原完整冻结载荷；缺少原请求时不从支付行补造用户、金额或方向。
    PayCallbackDTO request = recoveryRequest(bound.payload);
    PayVO payment = new PayVO();
    payment.payNo = lockedPayment.payNo;
    payment.userId = lockedPayment.userId;
    payment.amount = lockedPayment.amount;
    payment.status = lockedPayment.status;
    validateRequest(payment, request, callbackId);
    validatePayment(lockedPayment, payment, request);
    validateBound(bound, payment, request, callbackId);
    if (!finalStatus(request.success).equals(lockedPayment.status)) return;
    // 4. 仅复用已经锁定事实的日志 CAS，不调用 claim、支付编排或任何远程接口。
    completeLocked(lockedPayment, bound, payment, request, callbackId);
  }

  /** 已有支付和日志行锁下的完成原语；CAS 未命中必须报告冲突而非猜测成功。 */
  private void completeLocked(
      PayRecordPO lockedPayment,
      PayCallbackLogPO bound,
      PayVO payment,
      PayCallbackDTO request,
      String callbackId) {
    validateBound(bound, payment, request, callbackId);
    String expected = receivedStatus(request.success);
    if (!finalStatus(request.success).equals(lockedPayment.status)) throw conflict("支付尚未确认相应终态");
    if (finalStatus(request.success).equals(bound.callbackStatus)) {
      if (bound.processedAt == null) throw conflict("回调终态缺少处理时间");
      return;
    }
    if (!expected.equals(bound.callbackStatus)) throw conflict("回调方向与已绑定事实不一致");
    // 3. 仅 CAS 回调日志状态；支付状态由外部成功业务结果负责写入。
    if (callbackLogs.markProcessed(
            callbackId, expected, finalStatus(request.success), LocalDateTime.now())
        != 1) throw conflict("回调处理状态发生冲突");
  }

  /** 严格解析恢复所需四字段，拒绝重复键、尾随值和数值截断，不放宽公开历史兼容。 */
  private PayCallbackDTO recoveryRequest(String payload) {
    try {
      if (payload == null || payload.isBlank()) throw conflict("回调缺少完整冻结载荷");
      try (JsonParser parser = objectMapper.getFactory().createParser(payload)) {
        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        JsonNode node = objectMapper.readTree(parser);
        if (node == null
            || !node.isObject()
            || node.size() != 4
            || parser.nextToken() != null
            || !node.path("payNo").isTextual()
            || !node.path("userId").isIntegralNumber()
            || !node.path("userId").canConvertToLong()
            || node.path("userId").longValue() <= 0
            || !node.path("amount").isTextual()
            || !node.path("success").isBoolean()) throw conflict("回调缺少完整冻结载荷");
        PayCallbackDTO request = new PayCallbackDTO();
        request.payNo = node.get("payNo").textValue();
        request.userId = node.get("userId").longValue();
        request.amount = new BigDecimal(node.get("amount").textValue());
        request.success = node.get("success").booleanValue();
        return request;
      }
    } catch (BizException failure) {
      throw failure;
    } catch (Exception parseFailure) {
      throw conflict("回调冻结载荷无法验证");
    }
  }

  /** 锁定唯一支付记录。 */
  private PayRecordPO lockPayment(String payNo) {
    List<PayRecordPO> records = payments.selectForUpdate(payNo);
    if (records.size() != 1) throw conflict("支付记录不存在或不唯一");
    return records.get(0);
  }

  /** 校验请求参数完整且 callbackId 具备可持久化长度。 */
  private void validateRequest(PayVO payment, PayCallbackDTO request, String callbackId) {
    if (payment == null
        || request == null
        || callbackId == null
        || callbackId.isBlank()
        || callbackId.length() > 128
        || request.payNo == null
        || request.payNo.isBlank()
        || request.userId == null
        || request.amount == null
        || request.amount.signum() <= 0) {
      throw new BizException(ErrorCodes.INVALID, "回调参数不完整", 400);
    }
    if (!Objects.equals(payment.payNo, request.payNo)
        || !Objects.equals(payment.userId, request.userId)
        || payment.amount == null
        || payment.amount.compareTo(request.amount) != 0) {
      throw conflict("回调支付载荷不一致");
    }
  }

  /** 将数据库锁定行与外部支付视图、回调请求的不可变字段逐一比较。 */
  private void validatePayment(PayRecordPO locked, PayVO payment, PayCallbackDTO request) {
    if (!Objects.equals(locked.payNo, payment.payNo)
        || !Objects.equals(locked.payNo, request.payNo)
        || !Objects.equals(locked.userId, payment.userId)
        || !Objects.equals(locked.userId, request.userId)
        || locked.amount == null
        || locked.amount.compareTo(payment.amount) != 0
        || locked.amount.compareTo(request.amount) != 0) {
      throw conflict("支付原始载荷不一致");
    }
  }

  /** 构造只包含冻结字段的收到状态日志。 */
  private PayCallbackLogPO receivedLog(PayVO payment, PayCallbackDTO request, String callbackId) {
    LocalDateTime now = LocalDateTime.now();
    PayCallbackLogPO log = new PayCallbackLogPO();
    log.id = Math.abs(UUID.randomUUID().getMostSignificantBits());
    log.payNo = payment.payNo;
    log.callbackId = callbackId;
    log.callbackStatus = receivedStatus(request.success);
    log.payload = payload(request);
    log.processedAt = null;
    log.createdAt = now;
    return log;
  }

  /** 校验回调日志与当前请求的全局幂等绑定。 */
  private void validateBound(
      PayCallbackLogPO bound, PayVO payment, PayCallbackDTO request, String callbackId) {
    if (bound == null
        || !Objects.equals(bound.callbackId, callbackId)
        || !Objects.equals(bound.payNo, request.payNo)) {
      throw conflict("callbackId已绑定其他支付");
    }
    String expected = receivedStatus(request.success);
    String finalState = finalStatus(request.success);
    if (!expected.equals(bound.callbackStatus) && !finalState.equals(bound.callbackStatus))
      throw conflict("回调方向与已绑定事实不一致");
    validatePayload(bound.payload, payment, request);
  }

  /** 兼容历史空 payload，同时严格验证新完整 JSON 的冻结字段。 */
  private void validatePayload(String payload, PayVO payment, PayCallbackDTO request) {
    if ("{}".equals(payload == null ? null : payload.trim())) return;
    try {
      JsonNode node = objectMapper.readTree(payload);
      if (node == null
          || !node.path("payNo").isTextual()
          || !node.path("userId").isIntegralNumber()
          || !node.path("amount").isTextual()
          || !node.path("success").isBoolean()
          || !Objects.equals(node.get("payNo").asText(), request.payNo)
          || node.get("userId").asLong() != request.userId
          || node.get("success").asBoolean() != request.success
          || new BigDecimal(node.get("amount").asText()).compareTo(request.amount) != 0) {
        throw conflict("回调 payload 与支付事实不一致");
      }
    } catch (BizException exception) {
      throw exception;
    } catch (Exception parseFailure) {
      throw conflict("回调 payload 无法解析");
    }
  }

  /** 序列化回调不可变载荷，金额固定保存为字符串。 */
  private String payload(PayCallbackDTO request) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("payNo", request.payNo);
    fields.put("userId", request.userId);
    fields.put("amount", request.amount.toPlainString());
    fields.put("success", request.success);
    try {
      return objectMapper.writeValueAsString(fields);
    } catch (Exception serializationFailure) {
      throw new BizException(ErrorCodes.INTERNAL, "回调 payload 序列化失败", 500);
    }
  }

  /** 返回收到状态名。 */
  private static String receivedStatus(boolean success) {
    return success ? "RECEIVED_SUCCESS" : "RECEIVED_FAILURE";
  }

  /** 返回已处理状态名。 */
  private static String finalStatus(boolean success) {
    return success ? "SUCCESS" : "FAILED";
  }

  /** 构造 callbackId 绑定冲突。 */
  private static BizException conflict(String message) {
    return new BizException("PAY_CALLBACK_CONFLICT", message, 409);
  }
}
