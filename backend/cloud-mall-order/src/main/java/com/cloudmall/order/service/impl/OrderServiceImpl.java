package com.cloudmall.order.service.impl;

import com.cloudmall.common.api.*;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.*;
import com.cloudmall.order.config.OrderMessagingConfiguration;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.dto.OrderItemDTO;
import com.cloudmall.order.domain.dto.SeckillOrderDTO;
import com.cloudmall.order.domain.po.OrderIdempotencyPO;
import com.cloudmall.order.domain.po.OrderItemPO;
import com.cloudmall.order.domain.po.OrderOutboxPO;
import com.cloudmall.order.domain.po.OrderPO;
import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.domain.vo.OrderItemVO;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.OrderService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api")
public class OrderServiceImpl implements OrderService {
  /** 执行 ofPattern 相关操作。 */
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

  /** 保存 stock 的业务状态或配置。 */
  @Autowired private StockClient stock;

  /** 保存 products 的业务状态或配置。 */
  @Autowired private ProductClient products;

  /** 保存 users 的业务状态或配置。 */
  @Autowired private UserClient users;

  /** 保存 cart 的业务状态或配置。 */
  @Autowired private CartClient cart;

  /** 保存 db 的业务状态或配置。 */
  @Autowired private OrderSqlMapper orderSqlMapper;

  /** 保存 mapper 的业务状态或配置。 */
  @Autowired private ObjectMapper mapper;

  /** 在独立事务中保存普通订单预扣意图。 */
  @Autowired private OrderReservationIntentService reservationIntentService;

  /** 保存订单超时事件的 outbox Mapper。 */
  @Autowired private OrderOutboxMapper orderOutboxMapper;

  /** 在订单事务中更新意图状态的 Mapper。 */
  @Autowired private OrderReservationIntentMapper reservationIntentMapper;

  /** 与订单终态在同一事务提交的库存动作持久层。 */
  @Autowired private OrderStockActionMapper orderStockActionMapper;

  /** 创建由 Spring 字段注入依赖的订单服务实例。 */
  public OrderServiceImpl() {}

  /** 创建 OrderServiceImpl 实例。 */
  public OrderServiceImpl(
      StockClient stock,
      ProductClient products,
      UserClient users,
      CartClient cart,
      OrderSqlMapper orderSqlMapper,
      ObjectMapper mapper) {
    // 1. 接收并整理 OrderController 的业务请求。
    // 2. 执行 OrderController 的核心业务校验与状态处理。
    // 3. 返回 OrderController 的处理结果。
    this.stock = stock;
    this.products = products;
    this.users = users;
    this.cart = cart;
    this.orderSqlMapper = orderSqlMapper;
    this.mapper = mapper;
  }

  @Transactional
  @PostMapping("/orders")
  /** 执行 create 相关操作。 */
  public synchronized ApiResponse<?> create(
      @RequestHeader("Idempotency-Key") String key, @RequestBody CreateOrderDTO req) {
    // 1. 接收并整理 create 的业务请求。
    // 2. 执行 create 的核心业务校验与状态处理。
    // 3. 返回 create 的处理结果。
    long userId = AuthContext.requireUserId();
    if (key == null || key.isBlank()) bad("Idempotency-Key不能为空");
    List<String> old = orderSqlMapper.findIdempotentOrderNos(userId, key);
    if (!old.isEmpty()) return ApiResponse.ok(find(old.get(0)));
    if (req == null || req.items == null || req.items.isEmpty() || req.addressId == null)
      bad("订单商品和地址不能为空");
    Map<String, Object> address = findAddress(req.addressId);
    OffsetDateTime created = now();
    String orderNo = created.format(MONTH) + UUID.randomUUID().toString().replace("-", "");
    String table = table(created);
    List<StockClient.Line> lines = new ArrayList<>();
    List<ItemRow> rows = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (OrderItemDTO item : req.items) {
      if (item == null || item.skuId == null || item.quantity < 1) bad("商品数量必须为正数");
      ApiResponse<ProductClient.SkuView> resp;
      try {
        resp = products.sku(item.skuId);
      } catch (FeignException productFailure) {
        if (productFailure.status() == 404)
          throw new BizException(ErrorCodes.NOT_FOUND, "商品或 SKU 不存在", 404);
        throw new BizException(
            "GATEWAY_SERVICE_UNAVAILABLE", "商品服务暂不可用，请稍后重试", 503);
      }
      if (resp == null || !"0".equals(resp.code) || resp.data == null)
        throw new BizException(ErrorCodes.NOT_FOUND, "商品或 SKU 不存在", 404);
      ProductClient.SkuView skuView = resp.data;
      BigDecimal line = skuView.unitPrice().multiply(BigDecimal.valueOf(item.quantity));
      total = total.add(line);
      lines.add(new StockClient.Line(item.skuId, item.quantity));
      rows.add(new ItemRow(skuView, line, item.quantity));
    }
    OrderReservationIntentPO intent = new OrderReservationIntentPO();
    intent.id = id();
    intent.orderNo = orderNo;
    intent.userId = userId;
    intent.idempotencyKey = key;
    intent.itemsSnapshot = toJson(lines);
    intent.status = "INIT";
    intent.createdAt = created.toLocalDateTime();
    intent.updatedAt = intent.createdAt;
    if (!reservationIntentService.createIntent(intent)) {
      List<OrderReservationIntentPO> existing = reservationIntentService.findByUserAndKey(userId, key);
      if (!existing.isEmpty() && "COMMITTED".equals(existing.get(0).status))
        return ApiResponse.ok(find(existing.get(0).orderNo));
      throw new BizException(ErrorCodes.DUPLICATE, "同一幂等请求仍在处理中或已失败", 409);
    }

    boolean reservationConfirmed = false;
    boolean definiteRejection = false;
    try {
      // 1. 预扣请求始终使用已提交意图中的原订单号，网络结果未知时也可安全恢复。
      ApiResponse<?> reserved;
      try {
        reserved = stock.reserve(new StockClient.Reservation(orderNo, lines, "NORMAL"));
      } catch (RuntimeException remoteFailure) {
        if (remoteFailure instanceof FeignException feignFailure
            && feignFailure.status() == 409) {
          definiteRejection = true;
          reservationIntentService.markReleased(orderNo);
          throw new BizException(ErrorCodes.STOCK, "库存不足", 409);
        }
        BizException unknown =
            new BizException("ORDER_RESERVATION_UNKNOWN", "库存预扣结果待确认", 202);
        unknown.addSuppressed(remoteFailure);
        throw unknown;
      }
      if (reserved == null)
        throw new BizException("ORDER_RESERVATION_UNKNOWN", "库存预扣结果待确认", 202);
      if (!"0".equals(reserved.code)) {
        definiteRejection = true;
        reservationIntentService.markReleased(orderNo);
        throw new BizException(ErrorCodes.STOCK, "库存不足", 409);
      }
      reservationConfirmed = true;
      reservationIntentService.markReserved(orderNo);

      // 2. 订单、明细、幂等记录、超时 outbox 和 COMMITTED 意图同事务落库。
      OffsetDateTime expire = created.plusMinutes(30);
      OrderPO order = new OrderPO();
      order.id = id();
      order.orderNo = orderNo;
      order.userId = userId;
      order.status = "PENDING_PAYMENT";
      order.totalAmount = total;
      order.payAmount = total;
      order.addressSnapshot = toJson(address);
      order.expireAt = expire.toLocalDateTime();
      order.createdAt = created.toLocalDateTime();
      order.updatedAt = created.toLocalDateTime();
      orderSqlMapper.insertOrder(table, order);
      long orderId = orderSqlMapper.findOrderId(table, orderNo);
      String itemTable = table.replace("mall_order_", "mall_order_item_");
      for (ItemRow row : rows) {
        OrderItemPO orderItem = new OrderItemPO();
        orderItem.id = id();
        orderItem.orderId = orderId;
        orderItem.orderNo = orderNo;
        orderItem.productId = row.sku.productId();
        orderItem.skuId = row.sku.skuId();
        orderItem.productNameSnapshot = row.sku.productName();
        orderItem.skuSnapshot = toJson(row.sku.skuSnapshot());
        orderItem.unitPrice = row.sku.unitPrice();
        orderItem.quantity = row.quantity;
        orderItem.lineAmount = row.line;
        orderItem.createdAt = created.toLocalDateTime();
        orderSqlMapper.insertOrderItem(itemTable, orderItem);
      }
      OrderIdempotencyPO idempotency = new OrderIdempotencyPO();
      idempotency.id = id();
      idempotency.userId = userId;
      idempotency.idempotencyKey = key;
      idempotency.orderNo = orderNo;
      idempotency.createdAt = created.toLocalDateTime();
      orderSqlMapper.insertIdempotency(idempotency);
      orderOutboxMapper.insert(timeoutOutbox(orderNo, created, expire));
      for (OrderItemDTO item : req.items) {
        ApiResponse<?> removed = cart.deleteItem(item.skuId, userId);
        if (removed == null || !"0".equals(removed.code))
          throw new BizException(ErrorCodes.INTERNAL, "订单创建成功但购物车清理失败", 500);
      }
      OrderVO createdOrder = find(orderNo);
      if (reservationIntentMapper.markCommitted(orderNo, now().toLocalDateTime()) != 1)
        throw new BizException(ErrorCodes.CONFLICT, "库存预扣意图未能提交", 409);
      return ApiResponse.ok(createdOrder);
    } catch (RuntimeException creationFailure) {
      // 3. 只有成功响应才能同步回滚并结束意图；响应丢失须保留 INIT 等待恢复。
      if (reservationConfirmed) {
        compensateFailedCreate(orderNo, creationFailure);
      } else if (!definiteRejection) {
        try {
          reservationIntentService.markReserveUnknown(
              orderNo, "预扣结果待确认: " + creationFailure);
        } catch (RuntimeException recordFailure) {
          creationFailure.addSuppressed(recordFailure);
        }
      }
      throw creationFailure;
    }
  }

  @GetMapping("/orders")
  /** 执行 list 相关操作。 */
  public ApiResponse<?> list(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String startTime,
      @RequestParam(required = false) String endTime) {
    // 1. 接收并整理 list 的业务请求。
    // 2. 执行 list 的核心业务校验与状态处理。
    // 3. 返回 list 的处理结果。
    long userId = AuthContext.requireUserId();
    page = Math.max(1, page);
    pageSize = Math.min(Math.max(1, pageSize), 100);
    OffsetDateTime start = parse(startTime, now().minusMonths(1)),
        end = parse(endTime, now().plusSeconds(1));
    if (!start.isBefore(end)) throw new BizException(ErrorCodes.INVALID, "时间范围无效", 400);
    List<OrderVO> all = new ArrayList<>();
    YearMonth m = YearMonth.from(start), last = YearMonth.from(end.minusNanos(1));
    while (!m.isAfter(last)) {
      String tableName = "mall_order_" + m.format(MONTH);
      try {
        all.addAll(
            orderSqlMapper.findOrders(tableName, userId, ts(start), ts(end), status).stream()
                .map(this::view)
                .toList());
      } catch (DataAccessException ignored) {
      }
      m = m.plusMonths(1);
    }
    all.sort(
        Comparator.comparing(
            (OrderVO o) -> o.createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
    long total = all.size();
    int from = Math.min((page - 1) * pageSize, all.size()),
        to = Math.min(from + pageSize, all.size());
    return ApiResponse.ok(new PageResult<>(all.subList(from, to), page, pageSize, total));
  }

  @GetMapping("/orders/{orderNo}")
  /** 执行 get 相关操作。 */
  public ApiResponse<?> get(@PathVariable("orderNo") String orderNo) {
    // 1. 接收并整理 get 的业务请求。
    // 2. 执行 get 的核心业务校验与状态处理。
    // 3. 返回 get 的处理结果。
    return ApiResponse.ok(owned(orderNo));
  }

  /** 为支付恢复提供只读订单状态，不暴露支付服务持有的 payNo。 */
  @GetMapping("/internal/orders/{orderNo}/payment-state")
  public ApiResponse<?> paymentState(
      @PathVariable("orderNo") String orderNo,
      @RequestHeader(value = "X-User-Id", required = false) Long callerUserId) {
    // 1. 读取订单事实及内部调用者用户 ID。
    OrderVO order = find(orderNo);
    long userId = callerUserId == null ? AuthContext.requireUserId() : callerUserId;
    // 2. 校验订单归属，禁止跨用户探测支付状态。
    if (!Objects.equals(order.userId, userId))
      throw new BizException(ErrorCodes.FORBIDDEN, "无权访问此订单", 403);
    // 3. 只返回支付恢复所需的稳定字段。
    String actionStatus = orderStockActionMapper.statusForOrder(orderNo);
    if (actionStatus == null)
      actionStatus = "PENDING_PAYMENT".equals(order.status) ? "NONE" : "SUCCEEDED";
    return ApiResponse.ok(
        Map.of(
            "orderNo", order.orderNo,
            "status", order.status,
            "payAmount", order.payAmount,
            "userId", order.userId,
            "stockActionStatus", actionStatus));
  }

  @Transactional
  @PostMapping("/orders/{orderNo}/cancel")
  /** 执行 cancel 相关操作。 */
  public synchronized ApiResponse<?> cancel(
      @PathVariable("orderNo") String orderNo,
      @RequestHeader(value = "X-User-Id", required = false) Long headerUser) {
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

  @Transactional
  @PostMapping("/orders/{orderNo}/paid")
  /** 执行 paid 相关操作。 */
  public synchronized ApiResponse<?> paid(@PathVariable("orderNo") String orderNo) {
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
        || req == null
        || req.activityId == null
        || req.skuId == null
        || req.addressId == null)
      bad("秒杀参数不完整");
    Map<String, Object> address = findAddress(req.addressId);
    Map<String, Object> b = new HashMap<>();
    b.put("activityId", req.activityId);
    b.put("skuId", req.skuId);
    b.put("userId", userId);
    b.put("idempotencyKey", key);
    b.put("addressSnapshot", toJson(address));
    try {
      ApiResponse<?> row = stock.seckill(b);
      return row == null ? ApiResponse.error(ErrorCodes.INTERNAL, "库存服务不可用") : row;
    } catch (FeignException exception) {
      if (exception.status() == 429)
        throw new BizException("SECKILL_LIMIT_REACHED", "每位用户只能购买一次该秒杀商品", 429);
      if (exception.status() == 409)
        throw new BizException("SECKILL_NOT_AVAILABLE", "秒杀活动当前不可参与，请稍后再试", 409);
      throw new BizException(ErrorCodes.INTERNAL, "秒杀服务暂时不可用，请稍后再试", 503);
    }
  }

  @RabbitListener(queues = OrderMessagingConfiguration.SECKILL_QUEUE)
  @Transactional
  /** 执行 consumeSeckill 相关操作。 */
  public void consumeSeckill(Map<String, Object> event) {
    // 1. 接收并整理 consumeSeckill 的业务请求。
    // 2. 执行 consumeSeckill 的核心业务校验与状态处理。
    // 3. 返回 consumeSeckill 的处理结果。
    try {
      String orderNo = String.valueOf(event.get("orderNo")),
          key = String.valueOf(event.get("idempotencyKey"));
      long userId = ((Number) event.get("userId")).longValue();
      if (!orderSqlMapper.findIdempotentOrderNos(userId, key).isEmpty()) return;
      ProductClient.SkuView s = products.sku(((Number) event.get("skuId")).longValue()).data;
      if (s == null) throw new IllegalStateException("秒杀商品不存在: " + event.get("skuId"));
      OffsetDateTime created = now();
      String t = table(created);
      OrderPO order = new OrderPO();
      order.id = id();
      order.orderNo = orderNo;
      order.userId = userId;
      order.status = "PENDING_PAYMENT";
      order.totalAmount = s.unitPrice();
      order.payAmount = s.unitPrice();
      order.addressSnapshot = String.valueOf(event.getOrDefault("addressSnapshot", "{}"));
      order.expireAt = created.plusMinutes(30).toLocalDateTime();
      order.createdAt = created.toLocalDateTime();
      order.updatedAt = created.toLocalDateTime();
      orderSqlMapper.insertOrder(t, order);
      long orderId = orderSqlMapper.findOrderId(t, orderNo);
      String it = t.replace("mall_order_", "mall_order_item_");
      OrderItemPO orderItem = new OrderItemPO();
      orderItem.id = id();
      orderItem.orderId = orderId;
      orderItem.orderNo = orderNo;
      orderItem.productId = s.productId();
      orderItem.skuId = s.skuId();
      orderItem.productNameSnapshot = s.productName();
      orderItem.skuSnapshot = toJson(s.skuSnapshot());
      orderItem.unitPrice = s.unitPrice();
      orderItem.quantity = 1;
      orderItem.lineAmount = s.unitPrice();
      orderItem.createdAt = created.toLocalDateTime();
      orderSqlMapper.insertOrderItem(it, orderItem);
      OrderIdempotencyPO idempotency = new OrderIdempotencyPO();
      idempotency.id = id();
      idempotency.userId = userId;
      idempotency.idempotencyKey = key;
      idempotency.orderNo = orderNo;
      idempotency.createdAt = created.toLocalDateTime();
      orderSqlMapper.insertIdempotency(idempotency);
    } catch (Exception e) {
      throw new IllegalStateException("秒杀订单消息消费失败，交由 RabbitMQ 重试或死信: " + event, e);
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

  /** 执行 findAddress 相关操作。 */
  private Map<String, Object> findAddress(Long id) {
    // 1. 接收并整理 findAddress 的业务请求。
    // 2. 执行 findAddress 的核心业务校验与状态处理。
    // 3. 返回 findAddress 的处理结果。
    ApiResponse<List<Map<String, Object>>> row = users.addresses();
    if (row == null || row.data == null) throw new BizException(ErrorCodes.NOT_FOUND, "地址不存在", 404);
    return row.data.stream()
        .filter(a -> a.get("id") instanceof Number && ((Number) a.get("id")).longValue() == id)
        .findFirst()
        .orElseThrow(() -> new BizException(ErrorCodes.NOT_FOUND, "地址不存在", 404));
  }

  /** 执行 readSnapshot 相关操作。 */
  private Map<String, String> readSnapshot(String value) {
    // 1. 接收并整理 readSnapshot 的业务请求。
    // 2. 执行 readSnapshot 的核心业务校验与状态处理。
    // 3. 返回 readSnapshot 的处理结果。
    try {
      return value == null
          ? Map.of()
          : mapper.readValue(value, new TypeReference<Map<String, String>>() {});
    } catch (Exception e) {
      throw new BizException(ErrorCodes.INTERNAL, "SKU快照读取失败", 500);
    }
  }

  /** 执行 toJson 相关操作。 */
  private String toJson(Object v) {
    // 1. 接收并整理 toJson 的业务请求。
    // 2. 执行 toJson 的核心业务校验与状态处理。
    // 3. 返回 toJson 的处理结果。
    try {
      return mapper.writeValueAsString(v);
    } catch (Exception e) {
      throw new BizException(ErrorCodes.INTERNAL, "地址快照生成失败", 500);
    }
  }

  @RabbitListener(queues = OrderMessagingConfiguration.TIMEOUT_DLQ)
  @Transactional
  /** 消费新 JSON envelope 或存量 String 订单号超时消息。 */
  public void consumeTimeout(Message message) {
    // 1. 将两种消息表示统一为订单号；新消息必须携带稳定事件号和类型。
    String rawPayload = new String(message.getBody(), StandardCharsets.UTF_8);
    String orderNo;
    try {
      com.fasterxml.jackson.databind.JsonNode event = mapper.readTree(rawPayload);
      if (event.isTextual()) {
        orderNo = event.asText();
      } else if (event.isObject()
          && "ORDER_TIMEOUT".equals(event.path("eventType").asText())
          && !event.path("eventId").asText().isBlank()
          && !event.path("expireAt").asText().isBlank()) {
        orderNo = event.path("orderNo").asText();
      } else {
        throw new IllegalArgumentException("订单超时事件格式无效");
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException malformedLegacyMessage) {
      orderNo = rawPayload.trim();
    }
    // 2. 统一读取订单事实并通过 CAS 决定是否执行库存回滚。
    consumeTimeout(orderNo);
  }

  /** 按订单号处理旧版超时消息并供恢复测试复用。 */
  @Transactional
  public void consumeTimeout(String orderNo) {
    if (orderNo == null || orderNo.isBlank()) return;
    OrderVO order = find(orderNo);
    if (!"PENDING_PAYMENT".equals(order.status)) return;
    if (order.expireAt != null && now().isBefore(OffsetDateTime.parse(order.expireAt))) {
      throw new BizException(ErrorCodes.STATUS, "订单尚未到超时关单时间", 409);
    }
    if (setStatus(order, "CANCELLED"))
      orderStockActionMapper.insert(id(), orderNo, "ROLLBACK", now().toLocalDateTime());
  }

  /** 执行 find 相关操作。 */
  private OrderVO find(String orderNo) {
    // 1. 接收并整理 find 的业务请求。
    // 2. 执行 find 的核心业务校验与状态处理。
    // 3. 返回 find 的处理结果。
    String t = tableFromNo(orderNo);
    List<OrderVO> x = orderSqlMapper.findOrder(t, orderNo).stream().map(this::view).toList();
    if (x.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "订单不存在", 404);
    OrderVO o = x.get(0);
    o.items =
        orderSqlMapper
            .findOrderItems(t.replace("mall_order_", "mall_order_item_"), orderNo)
            .stream()
            .map(this::viewItem)
            .toList();
    return o;
  }

  /** 按旧状态执行 CAS 迁移，仅影响一行时返回成功。 */
  private boolean setStatus(OrderVO order, String targetStatus) {
    // 1. 接收并整理 setStatus 的业务请求。
    // 2. 执行 setStatus 的核心业务校验与状态处理。
    // 3. 返回 setStatus 的处理结果。
    return orderSqlMapper.updateStatus(
            tableFromNo(order.orderNo), order.orderNo, targetStatus, order.status, ts(now()))
        == 1;
  }

  /** 重新读取 CAS 竞争结果，仅目标状态视为幂等成功。 */
  private ApiResponse<?> completedOrConflict(String orderNo, String targetStatus) {
    OrderVO currentOrder = find(orderNo);
    if (targetStatus.equals(currentOrder.status)) return ApiResponse.ok(currentOrder);
    throw new BizException(ErrorCodes.STATUS, "订单状态已被其他操作更新", 409);
  }

  /** 构造与订单同事务保存的超时 outbox 事件。 */
  private OrderOutboxPO timeoutOutbox(
      String orderNo, OffsetDateTime createdAt, OffsetDateTime expireAt) {
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
    outbox.payload = toJson(payload);
    outbox.status = "PENDING";
    outbox.attempts = 0;
    outbox.nextRetryAt = createdAt.toLocalDateTime();
    outbox.createdAt = createdAt.toLocalDateTime();
    outbox.updatedAt = outbox.createdAt;
    return outbox;
  }

  /** 尝试同步回滚失败订单的远端库存，保留原失败供调用方处理。 */
  private void compensateFailedCreate(String orderNo, RuntimeException creationFailure) {
    try {
      ApiResponse<?> rollbackResult = stock.rollback(orderNo);
      if (rollbackResult == null || !"0".equals(rollbackResult.code))
        throw new BizException(ErrorCodes.INTERNAL, "订单失败后的库存回滚未确认", 500);
      reservationIntentService.markReleased(orderNo);
    } catch (RuntimeException compensationFailure) {
      try {
        reservationIntentService.recordFailure(orderNo, compensationFailure.toString());
      } catch (RuntimeException recordFailure) {
        compensationFailure.addSuppressed(recordFailure);
      }
      creationFailure.addSuppressed(compensationFailure);
    }
  }

  /** 将订单数据库行转换为订单响应视图。 */
  private OrderVO view(Map<String, Object> row) {
    // 1. 接收并整理 view 的业务请求。
    // 2. 执行 view 的核心业务校验与状态处理。
    // 3. 返回 view 的处理结果。
    OrderVO order = new OrderVO();
    order.orderNo = String.valueOf(row.get("order_no"));
    order.userId = ((Number) row.get("user_id")).longValue();
    order.status = String.valueOf(row.get("status"));
    order.totalAmount = (BigDecimal) row.get("total_amount");
    order.payAmount = (BigDecimal) row.get("pay_amount");
    order.addressSnapshot = String.valueOf(row.get("address_snapshot"));
    order.createdAt = timestamp(row.get("created_at"));
    order.expireAt = timestamp(row.get("expire_at"));
    return order;
  }

  /** 将订单明细数据库行转换为明细响应视图。 */
  private OrderItemVO viewItem(Map<String, Object> row) {
    return new OrderItemVO(
        ((Number) row.get("product_id")).longValue(),
        ((Number) row.get("sku_id")).longValue(),
        String.valueOf(row.get("product_name_snapshot")),
        readSnapshot((String) row.get("sku_snapshot")),
        (BigDecimal) row.get("unit_price"),
        ((Number) row.get("quantity")).intValue(),
        (BigDecimal) row.get("line_amount"));
  }

  /** 将数据库时间值格式化为 API 使用的 ISO-8601 时间。 */
  private static String timestamp(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return timestamp.toInstant().atOffset(ZoneOffset.ofHours(8)).toString();
    }
    if (value instanceof java.time.LocalDateTime localDateTime) {
      return localDateTime.atOffset(ZoneOffset.ofHours(8)).toString();
    }
    return String.valueOf(value);
  }

  /** 执行 table 相关操作。 */
  private static String table(OffsetDateTime d) {
    // 1. 接收并整理 table 的业务请求。
    // 2. 执行 table 的核心业务校验与状态处理。
    // 3. 返回 table 的处理结果。
    return "mall_order_" + d.format(MONTH);
  }

  /** 执行 tableFromNo 相关操作。 */
  private static String tableFromNo(String orderNo) {
    // 1. 接收并整理 tableFromNo 的业务请求。
    // 2. 执行 tableFromNo 的核心业务校验与状态处理。
    // 3. 返回 tableFromNo 的处理结果。
    if (orderNo == null || !orderNo.matches("\\d{6}[A-Za-z0-9]+"))
      throw new BizException(ErrorCodes.NOT_FOUND, "订单不存在", 404);
    try {
      YearMonth.parse(orderNo.substring(0, 6), DateTimeFormatter.ofPattern("yyyyMM"));
    } catch (Exception e) {
      throw new BizException(ErrorCodes.NOT_FOUND, "订单不存在", 404);
    }
    return "mall_order_" + orderNo.substring(0, 6);
  }

  /** 执行 parse 相关操作。 */
  private static OffsetDateTime parse(String s, OffsetDateTime d) {
    // 1. 接收并整理 parse 的业务请求。
    // 2. 执行 parse 的核心业务校验与状态处理。
    // 3. 返回 parse 的处理结果。
    try {
      return s == null ? d : OffsetDateTime.parse(s);
    } catch (Exception e) {
      throw new BizException(ErrorCodes.INVALID, "时间格式错误", 400);
    }
  }

  /** 执行 ts 相关操作。 */
  private static java.sql.Timestamp ts(OffsetDateTime d) {
    // 1. 接收并整理 ts 的业务请求。
    // 2. 执行 ts 的核心业务校验与状态处理。
    // 3. 返回 ts 的处理结果。
    return java.sql.Timestamp.from(d.toInstant());
  }

  /** 执行 now 相关操作。 */
  private static OffsetDateTime now() {
    // 1. 接收并整理 now 的业务请求。
    // 2. 执行 now 的核心业务校验与状态处理。
    // 3. 返回 now 的处理结果。
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }

  /** 执行 id 相关操作。 */
  private static long id() {
    // 1. 接收并整理 id 的业务请求。
    // 2. 执行 id 的核心业务校验与状态处理。
    // 3. 返回 id 的处理结果。
    return Math.abs(UUID.randomUUID().getMostSignificantBits());
  }

  /** 执行 bad 相关操作。 */
  private static void bad(String s) {
    // 1. 接收并整理 bad 的业务请求。
    // 2. 执行 bad 的核心业务校验与状态处理。
    // 3. 返回 bad 的处理结果。
    throw new BizException(ErrorCodes.INVALID, s, 400);
  }

  private record ItemRow(ProductClient.SkuView sku, BigDecimal line, int quantity) {}
}
