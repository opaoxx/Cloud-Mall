package com.cloudmall.order.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.order.domain.po.OrderOutboxPO;
import com.cloudmall.order.feign.UserClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/** 普通/秒杀写流程共享的原地址与JSON、超时消息和标识时间规则；不创建事务。 */
final class OrderWorkflowSupport {
  /** 原物理月表及订单号年月格式。 */
  static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

  /** 禁止实例化无状态的共享写流程规则。 */
  private OrderWorkflowSupport() {}

  /** 沿原User响应按数值地址ID取首个候选，缺失或空响应保持404。 */
  static Map<String, Object> findAddress(UserClient users, Long addressId) {
    ApiResponse<List<Map<String, Object>>> row = users.addresses();
    if (row == null || row.data == null) throw new BizException(ErrorCodes.NOT_FOUND, "地址不存在", 404);
    return row.data.stream()
        .filter(
            address ->
                address.get("id") instanceof Number
                    && ((Number) address.get("id")).longValue() == addressId)
        .findFirst()
        .orElseThrow(() -> new BizException(ErrorCodes.NOT_FOUND, "地址不存在", 404));
  }

  /** 使用原JSON配置生成快照，失败保持既有INTERNAL错误。 */
  static String toJson(ObjectMapper mapper, Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception serializationFailure) {
      throw new BizException(ErrorCodes.INTERNAL, "地址快照生成失败", 500);
    }
  }

  /** 从 Feign 错误响应读取下游业务码，避免把幂等冲突误报为库存不可用。 */
  static String remoteCode(ObjectMapper mapper, FeignException exception) {
    try {
      com.fasterxml.jackson.databind.JsonNode body = mapper.readTree(exception.contentUTF8());
      return body.path("code").asText();
    } catch (Exception ignored) {
      return "";
    }
  }

  /** 构造与订单同事务保存的超时 outbox 事件。 */
  static OrderOutboxPO timeoutOutbox(
      ObjectMapper mapper, String orderNo, OffsetDateTime createdAt, OffsetDateTime expireAt) {
    OrderOutboxPO outbox = new OrderOutboxPO();
    outbox.id = id();
    outbox.eventId = "order-timeout:" + orderNo;
    outbox.eventType = "ORDER_TIMEOUT";
    outbox.orderNo = orderNo;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("eventId", outbox.eventId);
    payload.put("eventType", outbox.eventType);
    payload.put("orderNo", orderNo);
    payload.put("expireAt", expireAt.toString());
    payload.put("occurredAt", createdAt.toString());
    payload.put("traceId", MDC.get("traceId"));
    outbox.payload = toJson(mapper, payload);
    outbox.status = "PENDING";
    outbox.attempts = 0;
    outbox.nextRetryAt = createdAt.toLocalDateTime();
    outbox.createdAt = createdAt.toLocalDateTime();
    outbox.updatedAt = outbox.createdAt;
    return outbox;
  }

  /** 按原年月格式选择创建目标物理表。 */
  static String table(OffsetDateTime dateTime) {
    return "mall_order_" + dateTime.format(MONTH);
  }

  /** 按同一instant生成原JDBC时间参数。 */
  static java.sql.Timestamp ts(OffsetDateTime dateTime) {
    return java.sql.Timestamp.from(dateTime.toInstant());
  }

  /** 保留原业务时间的固定上海偏移。 */
  static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }

  /** 保留原UUID高位绝对值标识生成规则。 */
  static long id() {
    return Math.abs(UUID.randomUUID().getMostSignificantBits());
  }

  /** 按原参数错误码和状态拒绝请求。 */
  static void bad(String message) {
    throw new BizException(ErrorCodes.INVALID, message, 400);
  }
}
