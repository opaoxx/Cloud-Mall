package com.cloudmall.order.service.impl;

import com.cloudmall.common.api.*;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.*;
import com.cloudmall.order.config.OrderMessagingConfiguration;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api")
@SuppressWarnings("unchecked")
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

  @Autowired(required = false)
  /** 保存 rabbit 的业务状态或配置。 */
  private RabbitTemplate rabbit;

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
      @RequestHeader("Idempotency-Key") String key, @RequestBody CreateRequest req) {
    // 1. 接收并整理 create 的业务请求。
    // 2. 执行 create 的核心业务校验与状态处理。
    // 3. 返回 create 的处理结果。
    long userId = AuthContext.requireUserId();
    if (key == null || key.isBlank()) bad("Idempotency-Key不能为空");
    List<String> old =
        orderSqlMapper.query(
            "select order_no from order_idempotency where user_id=? and idempotency_key=?",
            (row, rowNumber) -> row.getString(1),
            userId,
            key);
    if (!old.isEmpty()) return ApiResponse.ok(find(old.get(0)));
    if (req == null || req.items == null || req.items.isEmpty() || req.addressId == null)
      bad("订单商品和地址不能为空");
    OffsetDateTime created = now();
    String orderNo = created.format(MONTH) + UUID.randomUUID().toString().replace("-", "");
    String table = table(created);
    List<StockClient.Line> lines = new ArrayList<>();
    List<ItemRow> rows = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (Item i : req.items) {
      if (i == null || i.skuId == null || i.quantity < 1) bad("商品数量必须为正数");
      ApiResponse<ProductClient.SkuView> resp = products.sku(i.skuId);
      if (resp == null || !"0".equals(resp.code) || resp.data == null)
        throw new BizException(ErrorCodes.NOT_FOUND, "商品或 SKU 不存在", 404);
      ProductClient.SkuView s = resp.data;
      BigDecimal line = s.unitPrice().multiply(BigDecimal.valueOf(i.quantity));
      total = total.add(line);
      lines.add(new StockClient.Line(i.skuId, i.quantity));
      rows.add(new ItemRow(s, line, i.quantity));
    }
    ApiResponse<?> reserved = stock.reserve(new StockClient.Reservation(orderNo, lines, "NORMAL"));
    if (reserved == null || !"0".equals(reserved.code))
      throw new BizException(ErrorCodes.STOCK, "库存不足", 409);
    Map<String, Object> address = findAddress(req.addressId);
    OffsetDateTime expire = created.plusMinutes(30);
    orderSqlMapper.update(
        "insert into "
            + table
            + "(id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,expire_at,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?, ?,?,?)",
        id(),
        orderNo,
        userId,
        "PENDING_PAYMENT",
        total,
        total,
        toJson(address),
        ts(expire),
        ts(created),
        ts(created));
    long orderId =
        orderSqlMapper.queryForObject(
            "select id from " + table + " where order_no=?", Long.class, orderNo);
    String itemTable = table.replace("mall_order_", "mall_order_item_");
    for (ItemRow row : rows)
      orderSqlMapper.update(
          "insert into "
              + itemTable
              + "(id,order_id,order_no,product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?)",
          id(),
          orderId,
          orderNo,
          row.sku.productId(),
          row.sku.skuId(),
          row.sku.productName(),
          toJson(row.sku.skuSnapshot()),
          row.sku.unitPrice(),
          row.quantity,
          row.line,
          ts(created));
    orderSqlMapper.update(
        "insert into order_idempotency(id,user_id,idempotency_key,order_no,created_at)"
            + " values(?,?,?,?,?)",
        id(),
        userId,
        key,
        orderNo,
        ts(created));
    for (Item item : req.items) {
      ApiResponse<?> removed = cart.deleteItem(item.skuId, userId);
      if (removed == null || !"0".equals(removed.code))
        throw new BizException(ErrorCodes.INTERNAL, "订单创建成功但购物车清理失败", 500);
    }
    if (rabbit != null)
      rabbit.convertAndSend(
          "cloudmall.order.timeout.exchange",
          "",
          orderNo,
          m -> {
            m.getMessageProperties().setExpiration("1800000");
            return m;
          });
    return ApiResponse.ok(find(orderNo));
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
    List<Order> all = new ArrayList<>();
    YearMonth m = YearMonth.from(start), last = YearMonth.from(end.minusNanos(1));
    while (!m.isAfter(last)) {
      String t = "mall_order_" + m.format(MONTH);
      StringBuilder w = new StringBuilder(" where user_id=? and created_at>=? and created_at<?");
      List<Object> a = new ArrayList<>(List.of(userId, ts(start), ts(end)));
      if (status != null) {
        w.append(" and status=?");
        a.add(status);
      }
      try {
        all.addAll(
            orderSqlMapper.query(
                "select"
                    + " order_no,user_id,status,total_amount,pay_amount,address_snapshot,created_at,expire_at"
                    + " from "
                    + t
                    + w,
                a.toArray(),
                (row, rowNumber) -> view(row)));
      } catch (DataAccessException ignored) {
      }
      m = m.plusMonths(1);
    }
    all.sort(
        Comparator.comparing(
            (Order o) -> o.createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
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

  @Transactional
  @PostMapping("/orders/{orderNo}/cancel")
  /** 执行 cancel 相关操作。 */
  public synchronized ApiResponse<?> cancel(
      @PathVariable("orderNo") String orderNo,
      @RequestHeader(value = "X-User-Id", required = false) Long headerUser) {
    // 1. 接收并整理 cancel 的业务请求。
    // 2. 执行 cancel 的核心业务校验与状态处理。
    // 3. 返回 cancel 的处理结果。
    Order o = find(orderNo);
    long userId = headerUser == null ? AuthContext.requireUserId() : headerUser;
    if (o.userId != userId) throw new BizException(ErrorCodes.FORBIDDEN, "无权访问此订单", 403);
    if (!"PENDING_PAYMENT".equals(o.status)) return ApiResponse.ok(o);
    setStatus(o, "CANCELLED");
    stock.rollback(orderNo);
    return ApiResponse.ok(find(orderNo));
  }

  @Transactional
  @PostMapping("/orders/{orderNo}/paid")
  /** 执行 paid 相关操作。 */
  public synchronized ApiResponse<?> paid(@PathVariable("orderNo") String orderNo) {
    // 1. 接收并整理 paid 的业务请求。
    // 2. 执行 paid 的核心业务校验与状态处理。
    // 3. 返回 paid 的处理结果。
    Order o = owned(orderNo);
    if ("PAID".equals(o.status)) return ApiResponse.ok(o);
    if (!"PENDING_PAYMENT".equals(o.status))
      throw new BizException(ErrorCodes.STATUS, "订单状态不允许支付", 409);
    setStatus(o, "PAID");
    stock.confirm(orderNo);
    return ApiResponse.ok(find(orderNo));
  }

  @PostMapping("/orders/{orderNo}/confirm")
  /** 执行 confirm 相关操作。 */
  public ApiResponse<?> confirm(@PathVariable("orderNo") String orderNo) {
    // 1. 接收并整理 confirm 的业务请求。
    // 2. 执行 confirm 的核心业务校验与状态处理。
    // 3. 返回 confirm 的处理结果。
    Order o = owned(orderNo);
    if (!"PAID".equals(o.status)) throw new BizException(ErrorCodes.STATUS, "订单尚未支付", 409);
    setStatus(o, "COMPLETED");
    return ApiResponse.ok(find(orderNo));
  }

  @PostMapping("/seckill/orders")
  /** 执行 seckill 相关操作。 */
  public ApiResponse<?> seckill(
      @RequestHeader("Idempotency-Key") String key, @RequestBody SeckillRequest req) {
    // 1. 接收并整理 seckill 的业务请求。
    // 2. 执行 seckill 的核心业务校验与状态处理。
    // 3. 返回 seckill 的处理结果。
    long userId = AuthContext.requireUserId();
    if (key == null || key.isBlank() || req == null || req.activityId == null || req.skuId == null)
      bad("秒杀参数不完整");
    Map<String, Object> b = new HashMap<>();
    b.put("activityId", req.activityId);
    b.put("skuId", req.skuId);
    b.put("userId", userId);
    b.put("idempotencyKey", key);
    ApiResponse<?> row = stock.seckill(b);
    return row == null ? ApiResponse.error(ErrorCodes.INTERNAL, "库存服务不可用") : row;
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
      if (!orderSqlMapper
          .query(
              "select order_no from order_idempotency where user_id=? and idempotency_key=?",
              (row, rowNumber) -> row.getString(1),
              userId,
              key)
          .isEmpty()) return;
      ProductClient.SkuView s = products.sku(((Number) event.get("skuId")).longValue()).data;
      if (s == null) throw new IllegalStateException("秒杀商品不存在: " + event.get("skuId"));
      OffsetDateTime created = now();
      String t = table(created);
      orderSqlMapper.update(
          "insert into "
              + t
              + "(id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,expire_at,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?, ?,?,?)",
          id(),
          orderNo,
          userId,
          "PENDING_PAYMENT",
          s.unitPrice(),
          s.unitPrice(),
          "{}",
          ts(created.plusMinutes(30)),
          ts(created),
          ts(created));
      long orderId =
          orderSqlMapper.queryForObject(
              "select id from " + t + " where order_no=?", Long.class, orderNo);
      String it = t.replace("mall_order_", "mall_order_item_");
      orderSqlMapper.update(
          "insert into "
              + it
              + "(id,order_id,order_no,product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?)",
          id(),
          orderId,
          orderNo,
          s.productId(),
          s.skuId(),
          s.productName(),
          toJson(s.skuSnapshot()),
          s.unitPrice(),
          1,
          s.unitPrice(),
          ts(created));
      orderSqlMapper.update(
          "insert into order_idempotency(id,user_id,idempotency_key,order_no,created_at)"
              + " values(?,?,?,?,?)",
          id(),
          userId,
          key,
          orderNo,
          ts(created));
    } catch (Exception e) {
      throw new IllegalStateException("秒杀订单消息消费失败，交由 RabbitMQ 重试或死信: " + event, e);
    }
  }

  /** 执行 owned 相关操作。 */
  private Order owned(String orderNo) {
    // 1. 接收并整理 owned 的业务请求。
    // 2. 执行 owned 的核心业务校验与状态处理。
    // 3. 返回 owned 的处理结果。
    Order o = find(orderNo);
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
      return value == null ? Map.of() : mapper.readValue(value, Map.class);
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
  /** 执行 consumeTimeout 相关操作。 */
  public void consumeTimeout(String orderNo) {
    // 1. 接收并整理 consumeTimeout 的业务请求。
    // 2. 执行 consumeTimeout 的核心业务校验与状态处理。
    // 3. 返回 consumeTimeout 的处理结果。
    if (orderNo != null && !orderNo.isBlank()) {
      Order o = find(orderNo);
      if ("PENDING_PAYMENT".equals(o.status)) {
        setStatus(o, "CANCELLED");
        stock.rollback(orderNo);
      }
    }
  }

  /** 执行 find 相关操作。 */
  private Order find(String orderNo) {
    // 1. 接收并整理 find 的业务请求。
    // 2. 执行 find 的核心业务校验与状态处理。
    // 3. 返回 find 的处理结果。
    String t = tableFromNo(orderNo);
    List<Order> x =
        orderSqlMapper.query(
            "select"
                + " order_no,user_id,status,total_amount,pay_amount,address_snapshot,created_at,expire_at"
                + " from "
                + t
                + " where order_no=?",
            (row, rowNumber) -> view(row),
            orderNo);
    if (x.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "订单不存在", 404);
    Order o = x.get(0);
    o.items =
        orderSqlMapper.query(
            "select"
                + " product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount"
                + " from "
                + t.replace("mall_order_", "mall_order_item_")
                + " where order_no=? order by id",
            (row, rowNumber) ->
                new OrderItem(
                    row.getLong(1),
                    row.getLong(2),
                    row.getString(3),
                    readSnapshot(row.getString(4)),
                    row.getBigDecimal(5),
                    row.getInt(6),
                    row.getBigDecimal(7)),
            orderNo);
    return o;
  }

  /** 执行 setStatus 相关操作。 */
  private void setStatus(Order o, String st) {
    // 1. 接收并整理 setStatus 的业务请求。
    // 2. 执行 setStatus 的核心业务校验与状态处理。
    // 3. 返回 setStatus 的处理结果。
    orderSqlMapper.update(
        "update "
            + tableFromNo(o.orderNo)
            + " set status=?,updated_at=?,paid_at=case when ?='PAID' then ? else paid_at"
            + " end,cancelled_at=case when ?='CANCELLED' then ? else cancelled_at end where"
            + " order_no=? and status=?",
        st,
        ts(now()),
        st,
        ts(now()),
        st,
        ts(now()),
        o.orderNo,
        o.status);
  }

  /** 执行 view 相关操作。 */
  private static Order view(java.sql.ResultSet row) throws java.sql.SQLException {
    // 1. 接收并整理 view 的业务请求。
    // 2. 执行 view 的核心业务校验与状态处理。
    // 3. 返回 view 的处理结果。
    Order o = new Order();
    o.orderNo = row.getString(1);
    o.userId = row.getLong(2);
    o.status = row.getString(3);
    o.totalAmount = row.getBigDecimal(4);
    o.payAmount = row.getBigDecimal(5);
    o.addressSnapshot = row.getString(6);
    o.createdAt = row.getTimestamp(7).toInstant().atOffset(ZoneOffset.ofHours(8)).toString();
    if (row.getTimestamp(8) != null)
      o.expireAt = row.getTimestamp(8).toInstant().atOffset(ZoneOffset.ofHours(8)).toString();
    return o;
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

  public static class CreateRequest {
    /** 保存 items 的业务状态或配置。 */
    public List<Item> items;

    /** 保存 addressId 的业务状态或配置。 */
    public Long addressId;
  }

  public static class Item {
    /** 保存 skuId 的业务状态或配置。 */
    public Long skuId;

    /** 保存 quantity 的业务状态或配置。 */
    public int quantity;
  }

  public static class SeckillRequest {
    /** 保存 skuId 的业务状态或配置。 */
    public Long activityId, skuId;
  }

  private record ItemRow(ProductClient.SkuView sku, BigDecimal line, int quantity) {}

  public static class Order {
    /** 保存 addressSnapshot 的业务状态或配置。 */
    public String orderNo, status, createdAt, expireAt, addressSnapshot;

    /** 保存 userId 的业务状态或配置。 */
    public Long userId;

    /** 保存 payAmount 的业务状态或配置。 */
    public BigDecimal totalAmount, payAmount;

    /** 执行 业务操作 相关操作。 */
    public List<OrderItem> items = new ArrayList<>();
  }

  public record OrderItem(
      Long productId,
      Long skuId,
      String productNameSnapshot,
      Map<String, String> skuSnapshot,
      BigDecimal unitPrice,
      int quantity,
      BigDecimal lineAmount) {}
}
