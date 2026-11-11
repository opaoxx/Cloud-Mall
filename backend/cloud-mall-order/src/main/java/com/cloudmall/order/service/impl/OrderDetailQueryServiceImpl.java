package com.cloudmall.order.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.order.domain.vo.OrderPaymentStateVO;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.OrderDetailQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;

/** 无新增事务的详情与支付状态观察，保留原读取顺序及库存证明降级。 */
@Service
public class OrderDetailQueryServiceImpl implements OrderDetailQueryService {
  /** 调用方配置选择的订单月表访问实现。 */
  private final OrderSqlMapper orderSqlMapper;

  /** 用于原SKU快照反序列化的JSON配置。 */
  private final ObjectMapper mapper;

  /** 原订单终态动作证明访问。 */
  private final OrderStockActionMapper orderStockActionMapper;

  /** 存量终态无动作事实时使用的库存只读客户端。 */
  private final StockClient stock;

  /** 注入既有数据访问和远程读取依赖，不引入新事务或资源。 */
  public OrderDetailQueryServiceImpl(
      OrderSqlMapper orderSqlMapper,
      ObjectMapper mapper,
      OrderStockActionMapper orderStockActionMapper,
      StockClient stock) {
    this.orderSqlMapper = orderSqlMapper;
    this.mapper = mapper;
    this.orderStockActionMapper = orderStockActionMapper;
    this.stock = stock;
  }

  /** 主表与明细读取成功后才读取身份，保持原404/解析错误优先于认证失败。 */
  @Override
  public OrderVO get(String orderNo, LongSupplier userIdSupplier) {
    // 1. 复用完整详情读取，不添加RR或隐藏新的数据源。
    OrderVO order = OrderDetailReader.read(orderSqlMapper, mapper, orderNo);
    // 2. 在原owned位置读取身份并核对归属。
    if (!order.userId.equals(userIdSupplier.getAsLong()))
      throw new BizException(ErrorCodes.FORBIDDEN, "无权访问此订单", 403);
    return order;
  }

  /** 保留完整详情、身份、动作证明及必要Stock查询的先后次序。 */
  @Override
  public OrderPaymentStateVO paymentState(String orderNo, LongSupplier userIdSupplier) {
    // 1. 读取订单事实及内部调用者用户 ID。
    OrderVO order = OrderDetailReader.read(orderSqlMapper, mapper, orderNo);
    long userId = userIdSupplier.getAsLong();
    // 2. 校验订单归属，禁止跨用户探测支付状态。
    if (!Objects.equals(order.userId, userId))
      throw new BizException(ErrorCodes.FORBIDDEN, "无权访问此订单", 403);
    // 3. 已有动作必须匹配订单终态方向；真正无动作才进入原legacy只读兜底。
    String expectedActionType =
        "CANCELLED".equals(order.status)
            ? "ROLLBACK"
            : ("PAID".equals(order.status) || "COMPLETED".equals(order.status)) ? "CONFIRM" : null;
    String actionStatus = orderStockActionMapper.statusForOrder(orderNo, expectedActionType);
    if (actionStatus == null) actionStatus = legacyStockActionStatus(order);
    return new OrderPaymentStateVO(
        order.orderNo, order.status, order.payAmount, order.userId, actionStatus);
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
      if (response == null || !"0".equals(response.code) || response.data == null) return "UNKNOWN";
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
}
