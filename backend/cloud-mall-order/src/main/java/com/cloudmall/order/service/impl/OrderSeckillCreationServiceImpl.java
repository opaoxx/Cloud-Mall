package com.cloudmall.order.service.impl;

import static com.cloudmall.order.service.impl.OrderProductSnapshotRules.fitsOrderAmountCapacity;
import static com.cloudmall.order.service.impl.OrderProductSnapshotRules.hasCentPrecision;
import static com.cloudmall.order.service.impl.OrderProductSnapshotRules.hasNonnegativeProductPrice;
import static com.cloudmall.order.service.impl.OrderProductSnapshotRules.hasPersistableProductMetadata;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.id;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.now;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.timeoutOutbox;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.toJson;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.order.domain.po.OrderItemPO;
import com.cloudmall.order.domain.po.OrderPO;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.mapper.SeckillOrderDedupMapper;
import com.cloudmall.order.service.OrderSeckillCreationService;
import com.cloudmall.order.util.OrderCreationTime;
import com.cloudmall.order.util.OrderTableRouting;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 秒杀消息的原落单事务；幂等、证据、订单和outbox按既有顺序执行。 */
@Service
public class OrderSeckillCreationServiceImpl implements OrderSeckillCreationService {
  /** 首次落单使用的原SKU事实查询。 */
  private final ProductClient productClient;

  /** 订单及明细的原路由SQL入口。 */
  private final OrderSqlMapper orderSqlMapper;

  /** 原商品/冲突/超时事件序列化配置。 */
  private final ObjectMapper objectMapper;

  /** 与订单同事务写入的超时事件outbox。 */
  private final OrderOutboxMapper orderOutboxMapper;

  /** 消费幂等认领和冲突证据持久层。 */
  private final SeckillOrderDedupMapper seckillOrderDedupMapper;

  /** 按原业务身份完成匹配恢复记录的持久层。 */
  private final SeckillMessageRecoveryMapper seckillMessageRecoveryMapper;

  /** 注入原消息消费依赖，沿既有订单数据源参与同一事务。 */
  public OrderSeckillCreationServiceImpl(
      ProductClient productClient,
      OrderSqlMapper orderSqlMapper,
      ObjectMapper objectMapper,
      OrderOutboxMapper orderOutboxMapper,
      SeckillOrderDedupMapper seckillOrderDedupMapper,
      SeckillMessageRecoveryMapper seckillMessageRecoveryMapper) {
    this.productClient = productClient;
    this.orderSqlMapper = orderSqlMapper;
    this.objectMapper = objectMapper;
    this.orderOutboxMapper = orderOutboxMapper;
    this.seckillOrderDedupMapper = seckillOrderDedupMapper;
    this.seckillMessageRecoveryMapper = seckillMessageRecoveryMapper;
  }

  /**
   * 消费秒杀订单消息；首次落单仅接受成功状态且原SKU匹配的商品事实。
   *
   * @param event 原事件身份、请求指纹、SKU及创建时间快照
   * @throws IllegalStateException 消息、商品或落单失败保留cause，沿原Rabbit重试/死信流程处理
   */
  @Override
  @Transactional
  public void createFromMessage(Map<String, Object> event) {
    // 1. 校验原消息核心身份和请求指纹，冲突沿原证据流程处理。
    try {
      if (event == null) return;
      String orderNo = textOrDefault(event.get("orderNo"), "");
      String idempotencyKey = textOrDefault(event.get("idempotencyKey"), "");
      long userId;
      try {
        userId = longValue(event.get("userId"), "userId");
      } catch (RuntimeException invalidUser) {
        recordSeckillConflict(
            event,
            0L,
            idempotencyKey,
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
          || idempotencyKey.isBlank()
          || event.get("activityId") == null
          || event.get("skuId") == null
          || (!legacyMessage && event.get("requestHash") == null)) {
        recordSeckillConflict(
            event,
            userId,
            idempotencyKey,
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
            idempotencyKey,
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
            event, userId, idempotencyKey, computedHash, orderNo, eventId, "requestHash 与重算值不一致");
        return;
      }
      // 2. 原幂等认领和时间门先执行，已提交重放在Product之前收敛。
      int claimed =
          seckillOrderDedupMapper.claim(
              id(),
              userId,
              idempotencyKey,
              computedHash,
              orderNo,
              eventId,
              now().toLocalDateTime());
      Map<String, Object> dedup = seckillOrderDedupMapper.find(userId, idempotencyKey);
      if (claimed == 0) {
        if (dedup.isEmpty()) throw new IllegalStateException("秒杀幂等认领结果未知，保留消息重试");
        if (!computedHash.equalsIgnoreCase(String.valueOf(dedup.get("request_hash")))) {
          recordSeckillConflict(
              event, userId, idempotencyKey, computedHash, orderNo, eventId, "requestHash 冲突");
        }
        if (!orderNo.equals(String.valueOf(dedup.get("order_no")))) {
          recordSeckillConflict(
              event, userId, idempotencyKey, computedHash, orderNo, eventId, "orderNo 冲突");
        }
        if (computedHash.equalsIgnoreCase(String.valueOf(dedup.get("request_hash")))
            && orderNo.equals(String.valueOf(dedup.get("order_no")))) {
          if ("COMMITTED".equals(String.valueOf(dedup.get("status")))) {
            seckillMessageRecoveryMapper.markResolved(
                eventId, orderNo, userId, idempotencyKey, computedHash, now().toLocalDateTime());
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
      ApiResponse<ProductClient.SkuView> productResponse = productClient.sku(skuId);
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
      orderItem.skuSnapshot = toJson(objectMapper, productSku.skuSnapshot());
      orderItem.unitPrice = productSku.unitPrice();
      orderItem.quantity = 1;
      orderItem.lineAmount = productSku.unitPrice();
      orderItem.createdAt = created.toLocalDateTime();
      orderSqlMapper.insertOrderItem(itemTable, orderItem);
      orderOutboxMapper.insert(
          timeoutOutbox(objectMapper, orderNo, created, created.plusMinutes(30)));
      if (seckillOrderDedupMapper.markCommitted(userId, idempotencyKey, now().toLocalDateTime())
          != 1) throw new IllegalStateException("秒杀幂等记录未能提交: " + orderNo);
      if (eventId != null && !eventId.isBlank())
        seckillMessageRecoveryMapper.markResolved(
            eventId, orderNo, userId, idempotencyKey, computedHash, now().toLocalDateTime());
    } catch (Exception consumptionFailure) {
      throw new IllegalStateException("秒杀订单消息消费失败，交由 RabbitMQ 重试或死信: " + event, consumptionFailure);
    }
  }

  /** 仅对null使用原回退值，其他对象保持String.valueOf语义。 */
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
      String idempotencyKey,
      String requestHash,
      String orderNo,
      String eventId,
      String reason) {
    // 1. 准备唯一成功文本；保留原异常，交由外层Rabbit重试流程处理。
    String payload;
    try {
      payload = objectMapper.writeValueAsString(event);
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
        idempotencyKey,
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

  /** 对成功序列化的UTF-8文本计算原SHA-256，不重新序列化或猜测原消息字节。 */
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
}
