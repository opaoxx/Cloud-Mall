package com.cloudmall.order.service.impl;

import static com.cloudmall.order.service.impl.OrderProductSnapshotRules.fitsOrderAmountCapacity;
import static com.cloudmall.order.service.impl.OrderProductSnapshotRules.hasCentPrecision;
import static com.cloudmall.order.service.impl.OrderProductSnapshotRules.hasNonnegativeProductPrice;
import static com.cloudmall.order.service.impl.OrderProductSnapshotRules.hasPersistableProductMetadata;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.MONTH;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.bad;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.findAddress;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.id;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.now;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.remoteCode;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.table;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.timeoutOutbox;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.toJson;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.order.domain.dto.CartCleanupSnapshotDTO;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.dto.OrderItemDTO;
import com.cloudmall.order.domain.po.OrderIdempotencyPO;
import com.cloudmall.order.domain.po.OrderItemPO;
import com.cloudmall.order.domain.po.OrderPO;
import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.OrderCreationService;
import com.cloudmall.order.service.impl.creation.OrderCartCleanupCoordinator;
import com.cloudmall.order.service.impl.creation.OrderCreationLine;
import com.cloudmall.order.util.OrderCreationTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 普通订单创建事务、原意图协作及失败证明，HTTP包装由Controller完成。 */
@Service
public class OrderCreationServiceImpl implements OrderCreationService {
  /** 原Stock持久拒绝专用码，普通冲突不足以结束意图。 */
  private static final String DURABLE_RESERVATION_REJECTED = "ORDER_STOCK_RESERVATION_REJECTED";

  /** 创建单次采样的原上海时钟；隔离测试可注入固定时刻。 */
  private Clock creationClock = Clock.system(ZoneOffset.ofHours(8));

  /** 库存预扣与原补偿只读证明客户端。 */
  private final StockClient stock;

  /** 原商品SKU事实客户端。 */
  private final ProductClient products;

  /** 原地址事实客户端。 */
  private final UserClient users;

  /** 原Cart证明客户端。 */
  private final CartClient cart;

  /** 被选普通/分片订单持久层。 */
  private final OrderSqlMapper orderSqlMapper;

  /** 原JSON配置。 */
  private final ObjectMapper mapper;

  /** 原独立事务意图代理。 */
  private final OrderReservationIntentService reservationIntentService;

  /** 超时outbox持久层。 */
  private final OrderOutboxMapper orderOutboxMapper;

  /** 主事务中的COMMITTED意图更新。 */
  private final OrderReservationIntentMapper reservationIntentMapper;

  /** 与取消/支付共用的操作监视器。 */
  private final OrderWriteMonitor writeMonitor;

  /** 注入原业务依赖及共享监视器，不改变意图/主事务的资源边界。 */
  public OrderCreationServiceImpl(
      StockClient stock,
      ProductClient products,
      UserClient users,
      CartClient cart,
      OrderSqlMapper orderSqlMapper,
      ObjectMapper mapper,
      OrderReservationIntentService reservationIntentService,
      OrderOutboxMapper orderOutboxMapper,
      OrderReservationIntentMapper reservationIntentMapper,
      OrderWriteMonitor writeMonitor) {
    this.stock = stock;
    this.products = products;
    this.users = users;
    this.cart = cart;
    this.orderSqlMapper = orderSqlMapper;
    this.mapper = mapper;
    this.reservationIntentService = reservationIntentService;
    this.orderOutboxMapper = orderOutboxMapper;
    this.reservationIntentMapper = reservationIntentMapper;
    this.writeMonitor = writeMonitor;
  }

  /** 在原REQUIRED事务开始后获取共享监视器，完整保持创建/证明/补偿顺序。 */
  @Override
  @Transactional
  public OrderVO create(String key, CreateOrderDTO req, LongSupplier userIdSupplier) {
    synchronized (writeMonitor) {

      // 1. 读取原用户与订单幂等投影，直接重放不再次采样或写入。
      long userId = userIdSupplier.getAsLong();
      if (key == null || key.isBlank()) bad("Idempotency-Key不能为空");
      if (key.codePointCount(0, key.length()) > 128) bad("Idempotency-Key不能超过128个字符");
      List<OrderIdempotencyPO> existingOrders = orderSqlMapper.findIdempotentOrders(userId, key);
      if (!existingOrders.isEmpty()) {
        OrderIdempotencyPO existingOrder = existingOrders.get(0);
        requireExactIdempotencyKey(key, existingOrder.idempotencyKey);
        return OrderDetailReader.read(orderSqlMapper, mapper, existingOrder.orderNo);
      }
      if (req == null || req.items == null || req.items.isEmpty() || req.addressId == null)
        bad("订单商品和地址不能为空");
      // 2. 全集预检数量并只读捕获匹配购买聚合的原证明，后续不取最新Cart授权删除。
      Map<Long, Long> purchaseQuantities = new LinkedHashMap<>();
      for (OrderItemDTO item : req.items) {
        if (item == null || item.skuId == null || item.quantity < 1) bad("商品数量必须为正数");
        purchaseQuantities.merge(item.skuId, (long) item.quantity, Long::sum);
      }
      OrderCartCleanupCoordinator cartCleanupCoordinator =
          new OrderCartCleanupCoordinator(cart, mapper);
      List<CartCleanupSnapshotDTO> cleanupSnapshots =
          cartCleanupCoordinator.captureSnapshots(userId, purchaseQuantities);
      // 3. 按原流程校验地址/商品、预扣并持久化，成功末尾只注册原证明回调。
      Map<String, Object> address = findAddress(users, req.addressId);
      OffsetDateTime created = OrderCreationTime.normalize(OffsetDateTime.now(creationClock));
      String orderNo = created.format(MONTH) + UUID.randomUUID().toString().replace("-", "");
      String table = table(created);
      List<StockClient.Line> lines = new ArrayList<>();
      List<OrderCreationLine> rows = new ArrayList<>();
      BigDecimal total = BigDecimal.ZERO;
      for (OrderItemDTO item : req.items) {
        if (item == null || item.skuId == null || item.quantity < 1) bad("商品数量必须为正数");
        ApiResponse<ProductClient.SkuView> resp;
        try {
          resp = products.sku(item.skuId);
        } catch (FeignException productFailure) {
          if (productFailure.status() == 404)
            throw new BizException(ErrorCodes.NOT_FOUND, "商品或 SKU 不存在", 404);
          throw new BizException("GATEWAY_SERVICE_UNAVAILABLE", "商品服务暂不可用，请稍后重试", 503);
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
        rows.add(new OrderCreationLine(skuView, line, item.quantity));
      }
      // 全集商品和金额校验通过后一次性准备JSON，序列化失败不得创建意图或预扣库存。
      String addressSnapshotJson = toJson(mapper, address);
      List<String> skuSnapshotJsons = new ArrayList<>(rows.size());
      for (OrderCreationLine row : rows)
        skuSnapshotJsons.add(toJson(mapper, row.sku().skuSnapshot()));
      OrderReservationIntentPO intent = new OrderReservationIntentPO();
      intent.id = id();
      intent.orderNo = orderNo;
      intent.userId = userId;
      intent.idempotencyKey = key;
      intent.itemsSnapshot = toJson(mapper, lines);
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
            return OrderDetailReader.read(orderSqlMapper, mapper, existingIntent.orderNo);
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
              && DURABLE_RESERVATION_REJECTED.equals(remoteCode(mapper, feignFailure))) {
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
          OrderCreationLine row = rows.get(rowIndex);
          OrderItemPO orderItem = new OrderItemPO();
          orderItem.id = id();
          orderItem.orderId = orderId;
          orderItem.orderNo = orderNo;
          orderItem.productId = row.sku().productId();
          orderItem.skuId = row.sku().skuId();
          orderItem.productNameSnapshot = row.sku().productName();
          orderItem.skuSnapshot = skuSnapshotJsons.get(rowIndex);
          orderItem.unitPrice = row.sku().unitPrice();
          orderItem.quantity = row.quantity();
          orderItem.lineAmount = row.lineAmount();
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
        orderOutboxMapper.insert(timeoutOutbox(mapper, orderNo, created, expire));
        OrderVO createdOrder = OrderDetailReader.read(orderSqlMapper, mapper, orderNo);
        if (reservationIntentMapper.markCommitted(orderNo, now().toLocalDateTime()) != 1)
          throw new BizException(ErrorCodes.CONFLICT, "库存预扣意图未能提交", 409);
        cartCleanupCoordinator.registerAfterCommit(orderNo, userId, cleanupSnapshots);
        return createdOrder;
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
}
