package com.cloudmall.order.service.impl;

import static com.cloudmall.order.service.impl.OrderProductSnapshotRules.fitsOrderAmountCapacity;
import static com.cloudmall.order.service.impl.OrderProductSnapshotRules.hasCentPrecision;
import static com.cloudmall.order.service.impl.OrderProductSnapshotRules.hasNonnegativeProductPrice;
import static com.cloudmall.order.service.impl.OrderProductSnapshotRules.hasPersistableProductMetadata;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.bad;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.findAddress;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.id;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.now;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.remoteCode;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.timeoutOutbox;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.toJson;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.ts;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.cloudmall.common.api.*;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.*;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.order.config.OrderMessagingConfiguration;
import com.cloudmall.order.config.SeckillSentinelConfiguration;
import com.cloudmall.order.domain.dto.SeckillOrderDTO;
import com.cloudmall.order.domain.po.OrderItemPO;
import com.cloudmall.order.domain.po.OrderPO;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.mapper.SeckillOrderDedupMapper;
import com.cloudmall.order.service.OrderService;
import com.cloudmall.order.util.OrderCreationTime;
import com.cloudmall.order.util.OrderTableRouting;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import feign.FeignException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api")
public class OrderServiceImpl implements OrderService {
  /** 与普通创建Service共用的操作监视器；手动构造的隔离实例保有自身监视器。 */
  @Autowired private OrderWriteMonitor writeMonitor = new OrderWriteMonitor();

  /** 保存 stock 的业务状态或配置。 */
  @Autowired private StockClient stock;

  /** 保存 products 的业务状态或配置。 */
  @Autowired private ProductClient products;

  /** 保存 users 的业务状态或配置。 */
  @Autowired private UserClient users;

  /** 保存 db 的业务状态或配置。 */
  @Autowired private OrderSqlMapper orderSqlMapper;

  /** 保存 mapper 的业务状态或配置。 */
  @Autowired private ObjectMapper mapper;

  /** 保存订单超时事件的 outbox Mapper。 */
  @Autowired private OrderOutboxMapper orderOutboxMapper;

  /** 与订单终态在同一事务提交的库存动作持久层。 */
  @Autowired private OrderStockActionMapper orderStockActionMapper;

  /** 秒杀消费侧独立幂等记录。 */
  @Autowired private SeckillOrderDedupMapper seckillOrderDedupMapper;

  /** 秒杀死信恢复记录。 */
  @Autowired private SeckillMessageRecoveryMapper seckillMessageRecoveryMapper;

  /** 创建由 Spring 字段注入依赖的订单服务实例。 */
  public OrderServiceImpl() {}

  /** 手动装配剩余状态/秒杀流程；创建依赖由独立Service管理。 */
  public OrderServiceImpl(
      StockClient stock,
      ProductClient products,
      UserClient users,
      OrderSqlMapper orderSqlMapper,
      ObjectMapper mapper) {
    this.stock = stock;
    this.products = products;
    this.users = users;
    this.orderSqlMapper = orderSqlMapper;
    this.mapper = mapper;
  }

  @Transactional
  @PostMapping("/orders/{orderNo}/cancel")
  /** 执行 cancel 相关操作。 */
  public ApiResponse<?> cancel(
      @PathVariable("orderNo") String orderNo,
      @RequestHeader(value = "X-User-Id", required = false) Long headerUser) {
    synchronized (writeMonitor) {
      // 1. 接收并整理 cancel 的业务请求。
      // 2. 执行 cancel 的核心业务校验与状态处理。
      // 3. 返回 cancel 的处理结果。
      OrderVO order = find(orderNo);
      long userId = headerUser == null ? AuthContext.requireUserId() : headerUser;
      if (order.userId != userId) throw new BizException(ErrorCodes.FORBIDDEN, "无权访问此订单", 403);
      if ("CANCELLED".equals(order.status)) return ApiResponse.ok(order);
      if (!"PENDING_PAYMENT".equals(order.status))
        throw new BizException(ErrorCodes.STATUS, "订单状态不允许取消", 409);
      if (!setStatus(order, "CANCELLED")) return completedOrConflict(orderNo, "CANCELLED");
      orderStockActionMapper.insert(id(), orderNo, "ROLLBACK", now().toLocalDateTime());
      return ApiResponse.ok(find(orderNo));
    }
  }

  @Transactional
  @PostMapping("/orders/{orderNo}/paid")
  /** 执行 paid 相关操作。 */
  public ApiResponse<?> paid(@PathVariable("orderNo") String orderNo) {
    synchronized (writeMonitor) {
      // 1. 接收并整理 paid 的业务请求。
      // 2. 执行 paid 的核心业务校验与状态处理。
      // 3. 返回 paid 的处理结果。
      OrderVO order = owned(orderNo);
      if ("PAID".equals(order.status)) return ApiResponse.ok(order);
      if (!"PENDING_PAYMENT".equals(order.status))
        throw new BizException(ErrorCodes.STATUS, "订单状态不允许支付", 409);
      if (!setStatus(order, "PAID")) return completedOrConflict(orderNo, "PAID");
      orderStockActionMapper.insert(id(), orderNo, "CONFIRM", now().toLocalDateTime());
      return ApiResponse.ok(find(orderNo));
    }
  }

  @PostMapping("/orders/{orderNo}/confirm")
  /** 执行 confirm 相关操作。 */
  public ApiResponse<?> confirm(@PathVariable("orderNo") String orderNo) {
    // 1. 接收并整理 confirm 的业务请求。
    // 2. 执行 confirm 的核心业务校验与状态处理。
    // 3. 返回 confirm 的处理结果。
    OrderVO order = owned(orderNo);
    if ("COMPLETED".equals(order.status)) return ApiResponse.ok(order);
    if (!"PAID".equals(order.status)) throw new BizException(ErrorCodes.STATUS, "订单尚未支付", 409);
    if (!setStatus(order, "COMPLETED")) return completedOrConflict(orderNo, "COMPLETED");
    return ApiResponse.ok(find(orderNo));
  }

  @PostMapping("/seckill/orders")
  /** 执行 seckill 相关操作。 */
  public ApiResponse<?> seckill(
      @RequestHeader("Idempotency-Key") String key, @RequestBody SeckillOrderDTO req) {
    // 1. 接收并整理 seckill 的业务请求。
    // 2. 执行 seckill 的核心业务校验与状态处理。
    // 3. 返回 seckill 的处理结果。
    long userId = AuthContext.requireUserId();
    if (key == null
        || key.isBlank()
        || key.codePointCount(0, key.length()) > 128
        || req == null
        || req.activityId == null
        || req.skuId == null
        || req.addressId == null) bad("秒杀参数不完整");
    Entry sentinelEntry;
    try {
      sentinelEntry = SeckillSentinelConfiguration.enter(userId, req.activityId);
    } catch (BlockException blocked) {
      SeckillSentinelConfiguration.recordBlock();
      throw new BizException("GATEWAY_RATE_LIMITED", "请求过于频繁，请稍后重试", 429);
    }
    try {
      Map<String, Object> address = findAddress(users, req.addressId);
      Map<String, Object> stockRequest = new HashMap<>();
      stockRequest.put("activityId", req.activityId);
      stockRequest.put("skuId", req.skuId);
      stockRequest.put("userId", userId);
      stockRequest.put("idempotencyKey", key);
      stockRequest.put("addressSnapshot", toJson(mapper, address));
      try {
        ApiResponse<?> row =
            OrderStockCircuitBreaker.call(
                OrderStockCircuitBreaker.SECKILL, () -> stock.seckill(stockRequest));
        if (row == null)
          throw new BizException("GATEWAY_SERVICE_UNAVAILABLE", "秒杀服务暂时不可用，请稍后再试", 503);
        return row;
      } catch (FeignException exception) {
        if (exception.status() == 429) {
          String downstreamCode = remoteCode(mapper, exception);
          if ("GATEWAY_RATE_LIMITED".equals(downstreamCode))
            throw new BizException("GATEWAY_RATE_LIMITED", "请求过于频繁，请稍后重试", 429);
          if ("SECKILL_LIMIT_REACHED".equals(downstreamCode))
            throw new BizException("SECKILL_LIMIT_REACHED", "每位用户只能购买一次该秒杀商品", 429);
          throw new BizException("GATEWAY_SERVICE_UNAVAILABLE", "秒杀服务暂时不可用，请稍后再试", 503);
        }
        String downstreamCode = remoteCode(mapper, exception);
        if (exception.status() == 409 && "SECKILL_IDEMPOTENCY_CONFLICT".equals(downstreamCode))
          throw new BizException("SECKILL_IDEMPOTENCY_CONFLICT", "秒杀幂等键载荷已被占用", 409);
        if (exception.status() == 409
            && Set.of(
                    "SECKILL_NOT_STARTED",
                    "SECKILL_ENDED",
                    "SECKILL_SOLD_OUT",
                    "SECKILL_CLAIM_CONFLICT",
                    "COMMON_CONFLICT")
                .contains(downstreamCode))
          throw new BizException("SECKILL_NOT_AVAILABLE", "秒杀活动当前不可参与，请稍后再试", 409);
        throw new BizException("GATEWAY_SERVICE_UNAVAILABLE", "秒杀服务暂时不可用，请稍后再试", 503);
      } catch (StockCircuitOpenException circuitOpen) {
        throw new BizException("GATEWAY_SERVICE_UNAVAILABLE", "秒杀服务暂时不可用，请稍后再试", 503);
      }
    } finally {
      sentinelEntry.exit(1, userId, req.activityId);
    }
  }

  /**
   * 消费秒杀订单消息；首次落单仅接受成功状态且原SKU匹配的商品事实。
   *
   * @param event 原事件身份、请求指纹、SKU及创建时间快照
   * @throws IllegalStateException 消息、商品或落单失败保留cause，沿原Rabbit重试/死信流程处理
   */
  @RabbitListener(queues = OrderMessagingConfiguration.SECKILL_QUEUE)
  @Transactional
  public void consumeSeckill(Map<String, Object> event) {
    // 1. 校验原消息核心身份和请求指纹，冲突沿原证据流程处理。
    try {
      if (event == null) return;
      String orderNo = textOrDefault(event.get("orderNo"), "");
      String key = textOrDefault(event.get("idempotencyKey"), "");
      long userId;
      try {
        userId = longValue(event.get("userId"), "userId");
      } catch (RuntimeException invalidUser) {
        recordSeckillConflict(
            event,
            0L,
            key,
            textOrDefault(event.get("requestHash"), ""),
            orderNo,
            textOrDefault(event.get("eventId"), ""),
            "userId 缺失或非法");
        return;
      }
      boolean legacyMessage =
          "SECKILL_ORDER_ACCEPTED".equals(event.get("eventType"))
              && event.get("addressSnapshot") == null;
      if (orderNo.isBlank()
          || key.isBlank()
          || event.get("activityId") == null
          || event.get("skuId") == null
          || (!legacyMessage && event.get("requestHash") == null)) {
        recordSeckillConflict(
            event,
            userId,
            key,
            textOrDefault(event.get("requestHash"), ""),
            orderNo,
            textOrDefault(event.get("eventId"), ""),
            "秒杀消息核心字段或 requestHash 缺失");
        return;
      }
      long activityId;
      long skuId;
      try {
        activityId = longValue(event.get("activityId"), "activityId");
        skuId = longValue(event.get("skuId"), "skuId");
      } catch (RuntimeException invalidCore) {
        recordSeckillConflict(
            event,
            userId,
            key,
            textOrDefault(event.get("requestHash"), ""),
            orderNo,
            textOrDefault(event.get("eventId"), ""),
            "秒杀核心维度非法");
        return;
      }
      String addressSnapshot = textOrDefault(event.get("addressSnapshot"), "{}");
      String computedHash =
          SeckillRequestFingerprint.hash(activityId, skuId, userId, 1, addressSnapshot);
      String suppliedHash = textOrDefault(event.get("requestHash"), computedHash);
      String eventId =
          textOrDefault(event.get("eventId"), SeckillRequestFingerprint.eventId(orderNo));
      if (!legacyMessage && !computedHash.equalsIgnoreCase(suppliedHash)) {
        recordSeckillConflict(
            event, userId, key, computedHash, orderNo, eventId, "requestHash 与重算值不一致");
        return;
      }
      // 2. 原幂等认领和时间门先执行，已提交重放在Product之前收敛。
      int claimed =
          seckillOrderDedupMapper.claim(
              id(), userId, key, computedHash, orderNo, eventId, now().toLocalDateTime());
      Map<String, Object> dedup = seckillOrderDedupMapper.find(userId, key);
      if (claimed == 0) {
        if (dedup.isEmpty()) throw new IllegalStateException("秒杀幂等认领结果未知，保留消息重试");
        if (!computedHash.equalsIgnoreCase(String.valueOf(dedup.get("request_hash")))) {
          recordSeckillConflict(
              event, userId, key, computedHash, orderNo, eventId, "requestHash 冲突");
        }
        if (!orderNo.equals(String.valueOf(dedup.get("order_no")))) {
          recordSeckillConflict(event, userId, key, computedHash, orderNo, eventId, "orderNo 冲突");
        }
        if (computedHash.equalsIgnoreCase(String.valueOf(dedup.get("request_hash")))
            && orderNo.equals(String.valueOf(dedup.get("order_no")))) {
          if ("COMMITTED".equals(String.valueOf(dedup.get("status")))) {
            seckillMessageRecoveryMapper.markResolved(
                eventId, orderNo, userId, key, computedHash, now().toLocalDateTime());
            return;
          }
          throw new IllegalStateException("秒杀幂等记录仍在处理中，保留消息重试");
        }
        return;
      }
      // 已提交重复消息在上方收敛；首次落单必须先核验原时间和订单月份。
      OffsetDateTime created = OrderCreationTime.forSeckill(orderNo, event.get("occurredAt"));
      String orderTable = OrderTableRouting.tableFromOrderNo(orderNo);
      // 3. 首次落单先确认成功商品事实与原SKU，再写订单/明细/outbox并完成本地状态。
      ApiResponse<ProductClient.SkuView> productResponse = products.sku(skuId);
      if (productResponse == null || productResponse.data == null)
        throw new IllegalStateException("秒杀商品响应缺失: " + skuId);
      ProductClient.SkuView productSku = productResponse.data;
      if (!"0".equals(productResponse.code)
          || !Objects.equals(Long.valueOf(skuId), productSku.skuId()))
        throw new IllegalStateException("秒杀商品响应状态或SKU身份不可信: " + skuId);
      if (!hasNonnegativeProductPrice(productSku.unitPrice()))
        throw new IllegalStateException("秒杀商品价格不可信: " + skuId);
      if (!hasCentPrecision(productSku.unitPrice()))
        throw new IllegalStateException("秒杀商品价格小数精度不可信: " + skuId);
      if (!fitsOrderAmountCapacity(productSku.unitPrice()))
        throw new IllegalStateException("秒杀商品价格超出金额容量: " + skuId);
      if (!hasPersistableProductMetadata(productSku))
        throw new IllegalStateException("秒杀商品必填信息不可信: " + skuId);
      OrderPO order = new OrderPO();
      order.id = id();
      order.orderNo = orderNo;
      order.userId = userId;
      order.status = "PENDING_PAYMENT";
      order.totalAmount = productSku.unitPrice();
      order.payAmount = productSku.unitPrice();
      order.addressSnapshot = addressSnapshot;
      order.expireAt = created.plusMinutes(30).toLocalDateTime();
      order.createdAt = created.toLocalDateTime();
      order.updatedAt = created.toLocalDateTime();
      orderSqlMapper.insertOrder(orderTable, order);
      long orderId = orderSqlMapper.findOrderId(orderTable, orderNo);
      String itemTable = orderTable.replace("mall_order_", "mall_order_item_");
      OrderItemPO orderItem = new OrderItemPO();
      orderItem.id = id();
      orderItem.orderId = orderId;
      orderItem.orderNo = orderNo;
      orderItem.productId = productSku.productId();
      orderItem.skuId = productSku.skuId();
      orderItem.productNameSnapshot = productSku.productName();
      orderItem.skuSnapshot = toJson(mapper, productSku.skuSnapshot());
      orderItem.unitPrice = productSku.unitPrice();
      orderItem.quantity = 1;
      orderItem.lineAmount = productSku.unitPrice();
      orderItem.createdAt = created.toLocalDateTime();
      orderSqlMapper.insertOrderItem(itemTable, orderItem);
      orderOutboxMapper.insert(timeoutOutbox(mapper, orderNo, created, created.plusMinutes(30)));
      if (seckillOrderDedupMapper.markCommitted(userId, key, now().toLocalDateTime()) != 1)
        throw new IllegalStateException("秒杀幂等记录未能提交: " + orderNo);
      if (eventId != null && !eventId.isBlank())
        seckillMessageRecoveryMapper.markResolved(
            eventId, orderNo, userId, key, computedHash, now().toLocalDateTime());
    } catch (Exception consumptionFailure) {
      throw new IllegalStateException("秒杀订单消息消费失败，交由 RabbitMQ 重试或死信: " + event, consumptionFailure);
    }
  }

  private static String textOrDefault(Object value, String fallback) {
    return value == null ? fallback : String.valueOf(value);
  }

  /** 对当前消息身份表示执行无损long转换；禁止截断小数或回绕越界值，正数业务门仍由原指纹核验。 */
  private static long longValue(Object value, String fieldName) {
    try {
      if (value instanceof BigDecimal decimal) return decimal.longValueExact();
      if (value instanceof java.math.BigInteger integer) return integer.longValueExact();
      if (value instanceof Number number) return new BigDecimal(number.toString()).longValueExact();
      if (value instanceof String text) return Long.parseLong(text);
      throw new IllegalArgumentException("消息身份必须是数值或整数字符串");
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("秒杀消息字段非法: " + fieldName, exception);
    }
  }

  /** 先成功序列化一次，再以同一文本派生匿名标识并保存证据；失败不得用占位或toString替代原文。 */
  private void recordSeckillConflict(
      Map<String, Object> event,
      long userId,
      String key,
      String requestHash,
      String orderNo,
      String eventId,
      String reason) {
    // 1. 准备唯一成功文本；保留原异常，交由外层Rabbit重试流程处理。
    String payload;
    try {
      payload = mapper.writeValueAsString(event);
    } catch (Exception serializationFailure) {
      throw new IllegalStateException("秒杀冲突证据序列化失败", serializationFailure);
    }
    // 2. 保留原显式身份优先级，匿名标识和缺失哈希复用同一文本。
    String stableEventId =
        eventId == null || eventId.isBlank() ? stableSeckillEventId(payload, orderNo) : eventId;
    // 3. 只有真实文本准备完成后才写入JSON视图和原文证据。
    seckillOrderDedupMapper.recordConflictEvent(
        id(),
        userId,
        key,
        requestHash == null || requestHash.isBlank() ? sha256(payload) : requestHash,
        orderNo,
        stableEventId,
        reason,
        payload,
        now().toLocalDateTime());
  }

  /** 保留原订单派生标识，否则仅按已经成功序列化的文本计算稳定SHA256。 */
  private static String stableSeckillEventId(String payload, String orderNo) {
    if (orderNo != null && !orderNo.isBlank()) return SeckillRequestFingerprint.eventId(orderNo);
    return "legacy-seckill:hash:" + sha256(payload);
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JDK 缺少 SHA-256", exception);
    }
  }

  /** 执行 owned 相关操作。 */
  private OrderVO owned(String orderNo) {
    // 1. 接收并整理 owned 的业务请求。
    // 2. 执行 owned 的核心业务校验与状态处理。
    // 3. 返回 owned 的处理结果。
    OrderVO o = find(orderNo);
    if (!o.userId.equals(AuthContext.requireUserId()))
      throw new BizException(ErrorCodes.FORBIDDEN, "无权访问此订单", 403);
    return o;
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
    if (setStatus(order, "CANCELLED"))
      orderStockActionMapper.insert(id(), orderNo, "ROLLBACK", now().toLocalDateTime());
  }

  /** 复用原详情读取步骤，沿调用方既有事务执行。 */
  private OrderVO find(String orderNo) {
    return OrderDetailReader.read(orderSqlMapper, mapper, orderNo);
  }

  /** 按旧状态执行 CAS 迁移，仅影响一行时返回成功。 */
  private boolean setStatus(OrderVO order, String targetStatus) {
    // 1. 接收并整理 setStatus 的业务请求。
    // 2. 执行 setStatus 的核心业务校验与状态处理。
    // 3. 返回 setStatus 的处理结果。
    return orderSqlMapper.updateStatus(
            OrderTableRouting.tableFromOrderNo(order.orderNo),
            order.orderNo,
            targetStatus,
            order.status,
            ts(now()))
        == 1;
  }

  /** 重新读取 CAS 竞争结果，仅目标状态视为幂等成功。 */
  private ApiResponse<?> completedOrConflict(String orderNo, String targetStatus) {
    OrderVO currentOrder = find(orderNo);
    if (targetStatus.equals(currentOrder.status)) return ApiResponse.ok(currentOrder);
    throw new BizException(ErrorCodes.STATUS, "订单状态已被其他操作更新", 409);
  }

}
