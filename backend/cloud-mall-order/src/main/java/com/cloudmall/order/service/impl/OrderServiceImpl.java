package com.cloudmall.order.service.impl;

import static com.cloudmall.order.service.impl.OrderWorkflowSupport.id;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.now;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.order.config.OrderMessagingConfiguration;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.OrderService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

public class OrderServiceImpl implements OrderService {
  /** 保存 db 的业务状态或配置。 */
  @Autowired private OrderSqlMapper orderSqlMapper;

  /** 保存 mapper 的业务状态或配置。 */
  @Autowired private ObjectMapper mapper;

  /** 与订单终态在同一事务提交的库存动作持久层。 */
  @Autowired private OrderStockActionMapper orderStockActionMapper;

  /** 创建由 Spring 字段注入依赖的订单服务实例。 */
  public OrderServiceImpl() {}

  /** 手动装配尚未迁移的消息消费依赖。 */
  public OrderServiceImpl(OrderSqlMapper orderSqlMapper, ObjectMapper mapper) {
    this.orderSqlMapper = orderSqlMapper;
    this.mapper = mapper;
  }

  /**
   * 消费新JSON envelope或旧String订单号；裸数字正文保持原文本，不转数值标识。
   *
   * @param message 原UTF8超时消息，沿原格式解析后委托String业务入口
   * @throws IllegalArgumentException JSON根格式无效且未匹配既有legacy路径
   */
  @RabbitListener(queues = OrderMessagingConfiguration.TIMEOUT_DLQ)
  @Transactional
  public void consumeTimeout(Message message) {
    // 1. 将两种消息表示统一为订单号；新消息必须携带稳定事件号和类型。
    String rawPayload;
    try {
      // 解码先保原字节含义，损坏UTF8不得用替代字符猜测身份或降级旧格式。
      rawPayload =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(message.getBody()))
              .toString();
    } catch (CharacterCodingException invalidUtf8) {
      throw new IllegalArgumentException("订单超时事件不是合法UTF-8", invalidUtf8);
    }
    String orderNo;
    try {
      JsonNode event =
          mapper
              .reader()
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
              .readTree(rawPayload);
      if (event.isTextual()) {
        orderNo = event.asText();
      } else if (event.isIntegralNumber() && rawPayload.trim().matches("[0-9]+")) {
        // 旧裸数字String会被JSON读成整数；只认整份正文，避免long/浮点转换改写身份。
        orderNo = rawPayload.trim();
      } else if (event.isObject()
          && hasNonblankText(event, "eventType")
          && "ORDER_TIMEOUT".equals(event.path("eventType").textValue())
          && hasNonblankText(event, "eventId")
          && hasNonblankText(event, "orderNo")
          && hasNonblankText(event, "expireAt")) {
        orderNo = event.path("orderNo").textValue();
      } else {
        throw new IllegalArgumentException("订单超时事件格式无效");
      }
    } catch (MismatchedInputException invalidJsonRoot) {
      // 多根或重复键JSON不是旧订单号文本；保留解析证据，拒绝进入legacy或业务。
      throw new IllegalArgumentException("订单超时事件格式无效", invalidJsonRoot);
    } catch (com.fasterxml.jackson.core.JsonProcessingException malformedLegacyMessage) {
      orderNo = rawPayload.trim();
    }
    // 2. 统一读取订单事实并通过 CAS 决定是否执行库存回滚。
    consumeTimeout(orderNo);
  }

  /** 必填原生文本须非空白且代理对完整；JSON转义不保证Unicode完整性，不替换或归一化。 */
  private static boolean hasNonblankText(JsonNode event, String fieldName) {
    JsonNode fieldValue = event.path(fieldName);
    if (!fieldValue.isTextual() || fieldValue.textValue().isBlank()) return false;
    String text = fieldValue.textValue();
    // 逐UTF16单元线性检查，高代理与紧邻低代理同时消费，孤立低代理不得放行。
    for (int position = 0; position < text.length(); position++) {
      char codeUnit = text.charAt(position);
      if (Character.isHighSurrogate(codeUnit)) {
        if (position + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(position + 1)))
          return false;
        position++;
      } else if (Character.isLowSurrogate(codeUnit)) return false;
    }
    return true;
  }

  /** 按订单号处理旧版超时消息并供恢复测试复用。 */
  @Transactional
  public void consumeTimeout(String orderNo) {
    if (orderNo == null || orderNo.isBlank()) return;
    OrderVO order = find(orderNo);
    if (!"PENDING_PAYMENT".equals(order.status)) return;
    // 缺失DB期限无法证明已到期，不从消息或创建时间推导自动关单授权。
    if (order.expireAt == null) throw new BizException(ErrorCodes.STATUS, "订单超时期限缺失，暂不关单", 409);
    if (now().isBefore(OffsetDateTime.parse(order.expireAt))) {
      throw new BizException(ErrorCodes.STATUS, "订单尚未到超时关单时间", 409);
    }
    if (OrderStateWriter.transition(orderSqlMapper, order, "CANCELLED"))
      orderStockActionMapper.insert(id(), orderNo, "ROLLBACK", now().toLocalDateTime());
  }

  /** 复用原详情读取步骤，沿调用方既有事务执行。 */
  private OrderVO find(String orderNo) {
    return OrderDetailReader.read(orderSqlMapper, mapper, orderNo);
  }
}
