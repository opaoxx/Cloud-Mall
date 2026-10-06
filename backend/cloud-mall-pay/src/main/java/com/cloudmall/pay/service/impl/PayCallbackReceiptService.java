package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.domain.po.PayCallbackLogPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayCallbackLogMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 公开支付回调的独立预绑定与完成记录服务，不写支付业务状态。 */
@Service
public class PayCallbackReceiptService {
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
    // 1. 先核验请求和调用方已读取的支付不可变字段。
    validateRequest(payment, request, callbackId);
    // 2. 在独立本地事务中锁定真实支付行，避免视图与数据库载荷分叉。
    PayRecordPO lockedPayment = lockPayment(request.payNo);
    validatePayment(lockedPayment, payment, request);
    // 3. 预绑定完整事实后重新锁定日志，兼容并发插入和历史空 payload。
    PayCallbackLogPO candidate = receivedLog(payment, request, callbackId);
    callbackLogs.insertReceived(candidate);
    PayCallbackLogPO bound = callbackLogs.lockedSelect(callbackId);
    validateBound(bound, payment, request, callbackId);
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
