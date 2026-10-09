package com.cloudmall.order.service.impl;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.cloudmall.common.api.*;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.*;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.order.config.OrderMessagingConfiguration;
import com.cloudmall.order.config.SeckillSentinelConfiguration;
import com.cloudmall.order.domain.dto.CartCleanupSnapshotDTO;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.dto.OrderItemDTO;
import com.cloudmall.order.domain.dto.OrderQueryWindow;
import com.cloudmall.order.domain.dto.SeckillOrderDTO;
import com.cloudmall.order.domain.po.OrderIdempotencyPO;
import com.cloudmall.order.domain.po.OrderItemPO;
import com.cloudmall.order.domain.po.OrderOutboxPO;
import com.cloudmall.order.domain.po.OrderPO;
import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.domain.vo.OrderItemVO;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.mapper.SeckillOrderDedupMapper;
import com.cloudmall.order.service.OrderService;
import com.cloudmall.order.util.OrderCreationTime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api")
public class OrderServiceImpl implements OrderService {
  /** 跨月订单查询失败及错月数据的诊断日志。 */
  private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

  /** 既有订单/明细DECIMAL(18,2)非负数值容量上界；不表示商业限额或账户可支付余额。 */
  private static final BigDecimal MAX_ORDER_AMOUNT = new BigDecimal("9999999999999999.99");

  /** 执行 ofPattern 相关操作。 */
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

  /** Stock已持久化REJECTED的专用错误码，普通冲突不能授权结束预扣意图。 */
  private static final String DURABLE_RESERVATION_REJECTED = "ORDER_STOCK_RESERVATION_REJECTED";

  /** 常规订单创建的单次时钟来源；默认系统上海偏移，隔离测试可注入固定时刻。 */
  private Clock creationClock = Clock.system(ZoneOffset.ofHours(8));

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

  /** 秒杀消费侧独立幂等记录。 */
  @Autowired private SeckillOrderDedupMapper seckillOrderDedupMapper;

  /** 秒杀死信恢复记录。 */
  @Autowired private SeckillMessageRecoveryMapper seckillMessageRecoveryMapper;

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

  /**
   * 创建普通订单，入口采样数量匹配的原购物车证明，仅提交后条件清理。
   *
   * @param key 与原用户绑定的幂等请求键
   * @param req 购买商品数量及原收货地址请求
   * @return 创建成功或既有幂等重放的订单摘要
   * @throws BizException 请求或组合金额非法400，商品缺失404，响应商品事实不可信500，幂等/库存冲突409，预扣待确认202
   */
  @Transactional
  @PostMapping("/orders")
  public synchronized ApiResponse<?> create(
      @RequestHeader("Idempotency-Key") String key, @RequestBody CreateOrderDTO req) {
    // 1. 读取原用户与订单幂等投影，直接重放不再次采样或写入。
    long userId = AuthContext.requireUserId();
    if (key == null || key.isBlank()) bad("Idempotency-Key不能为空");
    if (key.codePointCount(0, key.length()) > 128) bad("Idempotency-Key不能超过128个字符");
    List<OrderIdempotencyPO> existingOrders = orderSqlMapper.findIdempotentOrders(userId, key);
    if (!existingOrders.isEmpty()) {
      OrderIdempotencyPO existingOrder = existingOrders.get(0);
      requireExactIdempotencyKey(key, existingOrder.idempotencyKey);
      return ApiResponse.ok(find(existingOrder.orderNo));
    }
    if (req == null || req.items == null || req.items.isEmpty() || req.addressId == null)
      bad("订单商品和地址不能为空");
    // 2. 全集预检数量并只读捕获匹配购买聚合的原证明，后续不取最新Cart授权删除。
    Map<Long, Long> purchaseQuantities = new LinkedHashMap<>();
    for (OrderItemDTO item : req.items) {
      if (item == null || item.skuId == null || item.quantity < 1) bad("商品数量必须为正数");
      purchaseQuantities.merge(item.skuId, (long) item.quantity, Long::sum);
    }
    List<CartCleanupSnapshotDTO> cleanupSnapshots =
        captureCartCleanupSnapshots(userId, purchaseQuantities);
    // 3. 按原流程校验地址/商品、预扣并持久化，成功末尾只注册原证明回调。
    Map<String, Object> address = findAddress(req.addressId);
    OffsetDateTime created = OrderCreationTime.normalize(OffsetDateTime.now(creationClock));
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
      // 商品事实必须属于本次请求SKU，错/缺响应身份不得授权金额、意图或库存。
      if (!Objects.equals(item.skuId, skuView.skuId()))
        throw new BizException(ErrorCodes.INTERNAL, "商品SKU响应身份不可信", 500);
      if (!hasNonnegativeProductPrice(skuView.unitPrice()))
        throw new BizException(ErrorCodes.INTERNAL, "商品价格不可信", 500);
      if (!hasCentPrecision(skuView.unitPrice()))
        throw new BizException(ErrorCodes.INTERNAL, "商品价格小数精度不可信", 500);
      if (!fitsOrderAmountCapacity(skuView.unitPrice()))
        throw new BizException(ErrorCodes.INTERNAL, "商品价格超出金额容量", 500);
      if (!hasPersistableProductMetadata(skuView))
        throw new BizException(ErrorCodes.INTERNAL, "商品必填信息不可信", 500);
      BigDecimal line = skuView.unitPrice().multiply(BigDecimal.valueOf(item.quantity));
      if (!fitsOrderAmountCapacity(line))
        throw new BizException(ErrorCodes.INVALID, "订单行金额超过支持范围", 400);
      BigDecimal nextTotal = total.add(line);
      if (!fitsOrderAmountCapacity(nextTotal))
        throw new BizException(ErrorCodes.INVALID, "订单总额超过支持范围", 400);
      total = nextTotal;
      lines.add(new StockClient.Line(item.skuId, item.quantity));
      rows.add(new ItemRow(skuView, line, item.quantity));
    }
    // 全集商品和金额校验通过后一次性准备JSON，序列化失败不得创建意图或预扣库存。
    String addressSnapshotJson = toJson(address);
    List<String> skuSnapshotJsons = new ArrayList<>(rows.size());
    for (ItemRow row : rows) skuSnapshotJsons.add(toJson(row.sku.skuSnapshot()));
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
      List<OrderReservationIntentPO> existing =
          reservationIntentService.findByUserAndKey(userId, key);
      if (!existing.isEmpty()) {
        OrderReservationIntentPO existingIntent = existing.get(0);
        requireExactIdempotencyKey(key, existingIntent.idempotencyKey);
        if ("COMMITTED".equals(existingIntent.status))
          return ApiResponse.ok(find(existingIntent.orderNo));
      }
      throw new BizException(ErrorCodes.DUPLICATE, "同一幂等请求仍在处理中或已失败", 409);
    }

    boolean reservationConfirmed = false;
    boolean definiteRejection = false;
    try {
      // 1. 预扣请求始终使用已提交意图中的原订单号，网络结果未知时也可安全恢复。
      ApiResponse<?> reserved;
      try {
        reserved =
            OrderStockCircuitBreaker.call(
                OrderStockCircuitBreaker.RESERVE,
                () -> stock.reserve(new StockClient.Reservation(orderNo, lines, "NORMAL")));
      } catch (RuntimeException remoteFailure) {
        if (remoteFailure instanceof FeignException feignFailure
            && feignFailure.status() == 409
            && DURABLE_RESERVATION_REJECTED.equals(remoteCode(feignFailure))) {
          definiteRejection = true;
          reservationIntentService.markReleased(orderNo);
          throw new BizException(ErrorCodes.STOCK, "库存不足", 409);
        }
        BizException unknown = new BizException("ORDER_RESERVATION_UNKNOWN", "库存预扣结果待确认", 202);
        unknown.addSuppressed(remoteFailure);
        throw unknown;
      }
      if (reserved == null) throw new BizException("ORDER_RESERVATION_UNKNOWN", "库存预扣结果待确认", 202);
      if (!"0".equals(reserved.code)) {
        // 非零响应不等于持久拒绝，只有专用证据才能结束原预扣意图。
        if (!DURABLE_RESERVATION_REJECTED.equals(reserved.code))
          throw new BizException("ORDER_RESERVATION_UNKNOWN", "库存预扣结果待确认", 202);
        definiteRejection = true;
        reservationIntentService.markReleased(orderNo);
        throw new BizException(ErrorCodes.STOCK, "库存不足", 409);
      }
      if (!validReservationProof(orderNo, reserved.data))
        throw new BizException("ORDER_RESERVATION_UNKNOWN", "库存预扣结果待确认", 202);
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
      order.addressSnapshot = addressSnapshotJson;
      order.expireAt = expire.toLocalDateTime();
      order.createdAt = created.toLocalDateTime();
      order.updatedAt = created.toLocalDateTime();
      orderSqlMapper.insertOrder(table, order);
      long orderId = orderSqlMapper.findOrderId(table, orderNo);
      String itemTable = table.replace("mall_order_", "mall_order_item_");
      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        ItemRow row = rows.get(rowIndex);
        OrderItemPO orderItem = new OrderItemPO();
        orderItem.id = id();
        orderItem.orderId = orderId;
        orderItem.orderNo = orderNo;
        orderItem.productId = row.sku.productId();
        orderItem.skuId = row.sku.skuId();
        orderItem.productNameSnapshot = row.sku.productName();
        orderItem.skuSnapshot = skuSnapshotJsons.get(rowIndex);
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
      OrderVO createdOrder = find(orderNo);
      if (reservationIntentMapper.markCommitted(orderNo, now().toLocalDateTime()) != 1)
        throw new BizException(ErrorCodes.CONFLICT, "库存预扣意图未能提交", 409);
      registerCartCleanup(orderNo, userId, cleanupSnapshots);
      return ApiResponse.ok(createdOrder);
    } catch (RuntimeException creationFailure) {
      // 3. 只有成功响应才能同步回滚并结束意图；响应丢失须保留 INIT 等待恢复。
      if (reservationConfirmed) {
        compensateFailedCreate(orderNo, creationFailure);
      } else if (!definiteRejection) {
        try {
          reservationIntentService.markReserveUnknown(orderNo, "预扣结果待确认: " + creationFailure);
        } catch (RuntimeException recordFailure) {
          creationFailure.addSuppressed(recordFailure);
        }
      }
      throw creationFailure;
    }
  }

  /** 只核原BigDecimal存在且数值非负；零价沿原兼容行为，不重建、舍入或改变scale。 */
  private static boolean hasNonnegativeProductPrice(BigDecimal unitPrice) {
    return unitPrice != null && unitPrice.signum() >= 0;
  }

  /** 仅在非空/非负价格门后核数学分精度，去尾零临时值不写回。先检查scale<=2， 避免极负scale整数去尾零下溢；不展开、舍入或修改原价格对象，不定义整数位容量。 */
  private static boolean hasCentPrecision(BigDecimal amount) {
    return amount.scale() <= 2 || amount.stripTrailingZeros().scale() <= 2;
  }

  /** 在非空/非负及分精度通过后只比较既有列数值上界，不展开、舍入或改变原金额表示。 */
  private static boolean fitsOrderAmountCapacity(BigDecimal amount) {
    return amount.compareTo(MAX_ORDER_AMOUNT) <= 0;
  }

  /** 仅核已存在商品事实的持久化必填字段和Unicode字符宽度，不截断/规范化或查询父关系。 */
  private static boolean hasPersistableProductMetadata(ProductClient.SkuView productSku) {
    return productSku.productId() != null
        && productSku.productName() != null
        && productSku.productName().codePointCount(0, productSku.productName().length()) <= 255;
  }

  /** 创建入口只读采样。只对购买聚合数量与原JSON正int数量一致的项提供不可变证明； long安全承载Java List中正int请求的总和，超int仅跳过清理，不改变订单流程。 */
  private List<CartCleanupSnapshotDTO> captureCartCleanupSnapshots(
      long userId, Map<Long, Long> purchaseQuantities) {
    // 1. 无真实事务或同步上下文时不调用Cart，也不猜测提交事实。
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      log.warn("订单购物车快照缺少实际事务上下文，保留购物车，userId={}", userId);
      return List.of();
    }
    // 2. 每个SKU只读一次，坏身份/数量或普通故障只保留该项，Error原传播。
    List<CartCleanupSnapshotDTO> captured = new ArrayList<>();
    for (Map.Entry<Long, Long> purchase : purchaseQuantities.entrySet()) {
      Long skuId = purchase.getKey();
      long purchasedQuantity = purchase.getValue();
      if (purchasedQuantity > Integer.MAX_VALUE) {
        log.warn("订单购买聚合数量超Cart范围，保留该项，userId={}，skuId={}", userId, skuId);
        continue;
      }
      try {
        ApiResponse<CartCleanupSnapshotDTO> response = cart.cleanupSnapshot(skuId, userId);
        if (response == null
            || !"0".equals(response.code)
            || !validCartCleanupSnapshot(skuId, purchasedQuantity, response.data))
          throw new IllegalStateException("购物车清理快照身份或购买数量未确认");
        captured.add(response.data);
      } catch (RuntimeException snapshotFailure) {
        log.warn("订单购物车前置快照捕获失败，保留该项，userId={}，skuId={}", userId, skuId, snapshotFailure);
      }
    }
    // 3. 保存原DTO，创建末尾与提交回调不GET重建或替换证明。
    return List.copyOf(captured);
  }

  /** 创建成功末尾只注册原证明回调，提交后条件删除，不重读Cart或改变原用户。 */
  private void registerCartCleanup(
      String orderNo, long userId, List<CartCleanupSnapshotDTO> cleanupSnapshots) {
    try {
      // 1. 无实际事务或同步上下文时不能证明提交，禁止提前删除购物车。
      if (!TransactionSynchronizationManager.isActualTransactionActive()
          || !TransactionSynchronizationManager.isSynchronizationActive()) {
        log.warn("订单购物车清理缺少实际事务上下文，保留购物车，orderNo={}", orderNo);
        return;
      }
      // 2. 只注册入口捕获的不可变证明，业务失败或rollback不执行POST。
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            /** 订单已提交后逐SKU清理；普通失败隔离，Error保持传播。 */
            @Override
            public void afterCommit() {
              // 3. 远端清理失败不得反向回滚已提交订单或库存，继续后续SKU。
              for (CartCleanupSnapshotDTO snapshot : cleanupSnapshots) {
                Long skuId = snapshot.skuId();
                try {
                  ApiResponse<Boolean> removed = cart.cleanupItem(skuId, userId, snapshot);
                  if (removed == null || !"0".equals(removed.code) || removed.data == null)
                    throw new IllegalStateException("购物车清理响应未确认");
                } catch (RuntimeException cleanupFailure) {
                  log.warn("订单提交后购物车清理失败，orderNo={}，skuId={}", orderNo, skuId, cleanupFailure);
                }
              }
            }
          });
    } catch (RuntimeException registrationFailure) {
      log.warn("订单购物车清理回调注册失败，保留购物车，orderNo={}", orderNo, registrationFailure);
    }
  }

  /** 原DTO/JSON数值SKU及原正int数量须匹配购买聚合，不从坏响应拼接或重建证明。 */
  private boolean validCartCleanupSnapshot(
      Long skuId, long purchasedQuantity, CartCleanupSnapshotDTO snapshot) {
    if (snapshot == null
        || !Objects.equals(skuId, snapshot.skuId())
        || snapshot.expectedJson() == null
        || snapshot.expectedJson().isBlank()) return false;
    try {
      JsonNode proof =
          mapper
              .reader()
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(snapshot.expectedJson());
      JsonNode proofSku = proof == null ? null : proof.path("skuId");
      JsonNode proofQuantity = proof == null ? null : proof.path("quantity");
      return proof != null
          && proof.isObject()
          && proofSku.isIntegralNumber()
          && proofSku.canConvertToLong()
          && proofSku.longValue() == skuId.longValue()
          && proofQuantity.isIntegralNumber()
          && proofQuantity.canConvertToInt()
          && proofQuantity.intValue() > 0
          && proofQuantity.longValue() == purchasedQuantity;
    } catch (JsonProcessingException malformedProof) {
      return false;
    }
  }

  /** 只核JSON原始文本身份和已接受状态，坏结构不授权；转换失败保留原异常证据。 */
  private boolean validReservationProof(String orderNo, Object data) {
    try {
      JsonNode proof = mapper.valueToTree(data);
      if (proof == null || !proof.isObject()) return false;
      JsonNode responseOrder = proof.path("orderNo");
      JsonNode responseStatus = proof.path("status");
      return responseOrder.isTextual()
          && responseStatus.isTextual()
          && Objects.equals(orderNo, responseOrder.textValue())
          && ("RESERVED".equals(responseStatus.textValue())
              || "ALREADY_RESERVED".equals(responseStatus.textValue()));
    } catch (IllegalArgumentException malformedProof) {
      BizException unknown = new BizException("ORDER_RESERVATION_UNKNOWN", "库存预扣结果待确认", 202);
      unknown.addSuppressed(malformedProof);
      throw unknown;
    }
  }

  /** 数据库排序规则命中只表示候选；原键不精确相等时拒绝重放，不修改旧事实。 */
  private static void requireExactIdempotencyKey(String requestedKey, String storedKey) {
    if (!Objects.equals(requestedKey, storedKey)) {
      throw new BizException(ErrorCodes.DUPLICATE, "Idempotency-Key与已有请求不完全一致", 409);
    }
  }

  /** 按上海时间路由月表，在同一数据库快照内计数和读取有界结果页。 */
  @GetMapping("/orders")
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ApiResponse<?> list(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String startTime,
      @RequestParam(required = false) String endTime) {
    // 1. 规范化请求时间和分页参数，避免其他 offset 的月初时间路由错表。
    long userId = AuthContext.requireUserId();
    page = Math.max(1, page);
    pageSize = Math.min(Math.max(1, pageSize), 100);
    OrderQueryWindow window = OrderQueryWindow.from(startTime, endTime, now());
    if (window.isEmpty()) {
      return ApiResponse.ok(new PageResult<OrderVO>(List.of(), page, pageSize, 0));
    }
    long offset;
    try {
      offset = Math.multiplyExact((long) page - 1L, (long) pageSize);
    } catch (ArithmeticException invalidPage) {
      throw new BizException(ErrorCodes.INVALID, "页码超出支持范围", 400);
    }
    java.sql.Timestamp rangeStart = ts(window.getStart());
    java.sql.Timestamp rangeEnd = ts(window.getEnd());
    YearMonth firstMonth = window.getFirstMonth();
    YearMonth lastMonth = window.getLastMonth();

    // 2. 逐月计数；任何一个月的读取失败都会让整次查询失败，不返回部分结果。
    Map<YearMonth, Long> monthlyCounts = new LinkedHashMap<>();
    long total = 0;
    for (YearMonth month = lastMonth; !month.isBefore(firstMonth); month = month.minusMonths(1)) {
      String tableName = "mall_order_" + month.format(MONTH);
      try {
        long monthCount =
            orderSqlMapper.countOrders(tableName, userId, rangeStart, rangeEnd, status);
        monthlyCounts.put(month, monthCount);
        total = Math.addExact(total, monthCount);
      } catch (DataAccessException | ArithmeticException countFailure) {
        throw unavailableOrderMonth(month, countFailure);
      }
    }

    // 3. 按月倒序跳过整月，只从目标页涉及的月表读取有界 LIMIT/OFFSET。
    List<OrderVO> items = new ArrayList<>(pageSize);
    if (offset < total) {
      long remainingOffset = offset;
      for (Map.Entry<YearMonth, Long> monthCount : monthlyCounts.entrySet()) {
        if (remainingOffset >= monthCount.getValue()) {
          remainingOffset -= monthCount.getValue();
          continue;
        }
        YearMonth month = monthCount.getKey();
        String tableName = "mall_order_" + month.format(MONTH);
        int limit =
            (int) Math.min((long) pageSize - items.size(), monthCount.getValue() - remainingOffset);
        List<Map<String, Object>> rows;
        try {
          rows =
              orderSqlMapper.findOrderPage(
                  tableName, userId, rangeStart, rangeEnd, status, remainingOffset, limit);
        } catch (DataAccessException pageFailure) {
          throw unavailableOrderMonth(month, pageFailure);
        }
        if (rows.size() != limit) {
          throw unavailableOrderMonth(
              month,
              new IllegalStateException("月表页结果数量与同一快照内的 COUNT 不一致: " + rows.size() + "/" + limit));
        }
        for (Map<String, Object> row : rows) {
          requireCorrectOrderMonth(row, month);
          items.add(view(row));
        }
        remainingOffset = 0;
        if (items.size() == pageSize) break;
      }
    }
    return ApiResponse.ok(new PageResult<>(items, page, pageSize, total));
  }

  /** 月表缺失、查询失败或分片数据异常时只返回明确失败。 */
  private static BizException unavailableOrderMonth(YearMonth month, RuntimeException failure) {
    log.error("订单月表查询失败，month={}", month, failure);
    return new BizException("ORDER_QUERY_SHARD_UNAVAILABLE", "订单列表暂不可用，请稍后重试", 503);
  }

  /** 发现目标页中的错月订单时拒绝按月份拼接出错误顺序。 */
  private static void requireCorrectOrderMonth(Map<String, Object> row, YearMonth month) {
    Object createdAt = row.get("created_at");
    LocalDateTime createdLocal =
        createdAt instanceof java.sql.Timestamp timestamp
            ? timestamp.toLocalDateTime()
            : createdAt instanceof LocalDateTime localDateTime ? localDateTime : null;
    if (createdLocal == null || !YearMonth.from(createdLocal).equals(month)) {
      throw unavailableOrderMonth(
          month, new IllegalStateException("订单行的 created_at 不属于所在月表"));
    }
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
    // 3. 已有动作必须匹配订单终态方向；真正无动作才进入原legacy只读兜底。
    String expectedActionType =
        "CANCELLED".equals(order.status)
            ? "ROLLBACK"
            : ("PAID".equals(order.status) || "COMPLETED".equals(order.status)) ? "CONFIRM" : null;
    String actionStatus = orderStockActionMapper.statusForOrder(orderNo, expectedActionType);
    if (actionStatus == null)
      actionStatus = legacyStockActionStatus(order);
    return ApiResponse.ok(
        Map.of(
            "orderNo", order.orderNo,
            "status", order.status,
            "payAmount", order.payAmount,
            "userId", order.userId,
            "stockActionStatus", actionStatus));
  }

  /** 存量终态无动作行时，必须用库存持久事实确认，不能把旧异常订单视为已结清。 */
  private String legacyStockActionStatus(OrderVO order) {
    // 1. 待支付订单尚未产生最终库存动作。
    if ("PENDING_PAYMENT".equals(order.status)) return "NONE";
    // 2. 未知订单状态不能推断库存方向，不查询或修复历史坏行。
    if (!"PAID".equals(order.status)
        && !"COMPLETED".equals(order.status)
        && !"CANCELLED".equals(order.status)) return "UNKNOWN";
    // 3. 按明确订单终态确定允许结清的库存终态及相反状态。
    String expected = "CANCELLED".equals(order.status) ? "ROLLED_BACK" : "CONFIRMED";
    String opposite = "CANCELLED".equals(order.status) ? "CONFIRMED" : "ROLLED_BACK";
    try {
      // 4. 只读取库存持久事实；旧记录缺失或服务不可用时保持未知。
      ApiResponse<com.cloudmall.order.domain.vo.StockReservationStatusVO> response =
          OrderStockCircuitBreaker.call(
              OrderStockCircuitBreaker.STATUS, () -> stock.reservation(order.orderNo));
      if (response == null || !"0".equals(response.code) || response.data == null)
        return "UNKNOWN";
      // 缺失或错单响应保持未知，不能借别单终态作为结清或退款依据。
      if (!Objects.equals(order.orderNo, response.data.orderNo)) return "UNKNOWN";
      if (expected.equals(response.data.status)) return "SUCCEEDED";
      if (opposite.equals(response.data.status) || "REJECTED".equals(response.data.status))
        return "CONFLICT";
      return "UNKNOWN";
    } catch (RuntimeException stockQueryFailure) {
      return "UNKNOWN";
    }
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
      Map<String, Object> address = findAddress(req.addressId);
      Map<String, Object> stockRequest = new HashMap<>();
      stockRequest.put("activityId", req.activityId);
      stockRequest.put("skuId", req.skuId);
      stockRequest.put("userId", userId);
      stockRequest.put("idempotencyKey", key);
      stockRequest.put("addressSnapshot", toJson(address));
      try {
        ApiResponse<?> row = OrderStockCircuitBreaker.call(OrderStockCircuitBreaker.SECKILL,
            () -> stock.seckill(stockRequest));
        if (row == null)
          throw new BizException("GATEWAY_SERVICE_UNAVAILABLE", "秒杀服务暂时不可用，请稍后再试", 503);
        return row;
      } catch (FeignException exception) {
        if (exception.status() == 429) {
          String downstreamCode = remoteCode(exception);
          if ("GATEWAY_RATE_LIMITED".equals(downstreamCode))
            throw new BizException("GATEWAY_RATE_LIMITED", "请求过于频繁，请稍后重试", 429);
          if ("SECKILL_LIMIT_REACHED".equals(downstreamCode))
            throw new BizException("SECKILL_LIMIT_REACHED", "每位用户只能购买一次该秒杀商品", 429);
          throw new BizException("GATEWAY_SERVICE_UNAVAILABLE", "秒杀服务暂时不可用，请稍后再试", 503);
        }
        String downstreamCode = remoteCode(exception);
        if (exception.status() == 409 && "SECKILL_IDEMPOTENCY_CONFLICT".equals(downstreamCode))
          throw new BizException("SECKILL_IDEMPOTENCY_CONFLICT", "秒杀幂等键载荷已被占用", 409);
        if (exception.status() == 409 && Set.of("SECKILL_NOT_STARTED", "SECKILL_ENDED",
                "SECKILL_SOLD_OUT", "SECKILL_CLAIM_CONFLICT", "COMMON_CONFLICT")
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

  /** 从 Feign 错误响应读取下游业务码，避免把幂等冲突误报为库存不可用。 */
  private String remoteCode(FeignException exception) {
    try {
      com.fasterxml.jackson.databind.JsonNode body = mapper.readTree(exception.contentUTF8());
      return body.path("code").asText();
    } catch (Exception ignored) {
      return "";
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
      String orderTable = tableFromNo(orderNo);
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
      orderItem.skuSnapshot = toJson(productSku.skuSnapshot());
      orderItem.unitPrice = productSku.unitPrice();
      orderItem.quantity = 1;
      orderItem.lineAmount = productSku.unitPrice();
      orderItem.createdAt = created.toLocalDateTime();
      orderSqlMapper.insertOrderItem(itemTable, orderItem);
      orderOutboxMapper.insert(timeoutOutbox(orderNo, created, created.plusMinutes(30)));
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
      return java.util.HexFormat.of().formatHex(
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
    String rawPayload = new String(message.getBody(), StandardCharsets.UTF_8);
    String orderNo;
    try {
      com.fasterxml.jackson.databind.JsonNode event = mapper.readTree(rawPayload);
      if (event.isTextual()) {
        orderNo = event.asText();
      } else if (event.isIntegralNumber() && rawPayload.trim().matches("[0-9]+")) {
        // 旧裸数字String会被JSON读成整数；只认整份正文，避免long/浮点转换改写身份。
        orderNo = rawPayload.trim();
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

  /** 在映射视图和读取明细前绑定请求号与主表原身份，错单候选沿原404拒绝。 */
  private OrderVO find(String orderNo) {
    // 1. 沿原格式/月校验查询主表原始候选，不改变数据库比较规则。
    String orderTable = tableFromNo(orderNo);
    List<Map<String, Object>> orderRows = orderSqlMapper.findOrder(orderTable, orderNo);
    // 2. 无行或身份不精确时拒绝，禁止借别名继续明细、鉴权或后续状态操作。
    if (orderRows.isEmpty() || !Objects.equals(orderNo, orderRows.get(0).get("order_no")))
      throw new BizException(ErrorCodes.NOT_FOUND, "订单不存在", 404);
    // 3. 身份有效后保持原视图和明细顺序，调用方继续原鉴权与业务流程。
    List<OrderVO> orders = orderRows.stream().map(this::view).toList();
    OrderVO order = orders.get(0);
    order.items =
        orderSqlMapper
            .findOrderItems(orderTable.replace("mall_order_", "mall_order_item_"), orderNo)
            .stream()
            .map(this::viewItem)
            .toList();
    return order;
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

  /** 同步回滚后核验原单持久终态，未知证明保留意图和原创建异常供恢复。 */
  private void compensateFailedCreate(String orderNo, RuntimeException creationFailure) {
    try {
      // 1. 使用原订单号尝试一次同步回滚，失败响应不能授权后续完成。
      ApiResponse<?> rollbackResult =
          OrderStockCircuitBreaker.call(
              OrderStockCircuitBreaker.ACTION, () -> stock.rollback(orderNo));
      if (rollbackResult == null || !"0".equals(rollbackResult.code))
        throw new BizException(ErrorCodes.INTERNAL, "订单失败后的库存回滚未确认", 500);
      // 2. 成功响应后只读核验原单持久终态，不补全身份或猜测结算结果。
      ApiResponse<StockReservationStatusVO> proof =
          OrderStockCircuitBreaker.call(
              OrderStockCircuitBreaker.STATUS, () -> stock.reservation(orderNo));
      if (proof == null
          || !"0".equals(proof.code)
          || proof.data == null
          || !Objects.equals(orderNo, proof.data.orderNo)
          || (!"ROLLED_BACK".equals(proof.data.status) && !"REJECTED".equals(proof.data.status))) {
        throw new BizException(ErrorCodes.INTERNAL, "订单失败后的库存回滚持久事实未确认", 500);
      }
      // 3. 只有完整证明才结束意图，失败沿原记录与suppressed保留创建异常。
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
    if (orderNo == null || !orderNo.matches("\\d{6}[A-Za-z0-9-]+"))
      throw new BizException(ErrorCodes.NOT_FOUND, "订单不存在", 404);
    try {
      YearMonth.parse(orderNo.substring(0, 6), DateTimeFormatter.ofPattern("yyyyMM"));
    } catch (Exception e) {
      throw new BizException(ErrorCodes.NOT_FOUND, "订单不存在", 404);
    }
    return "mall_order_" + orderNo.substring(0, 6);
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
