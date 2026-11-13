package com.cloudmall.order.service.impl;

import static com.cloudmall.order.service.impl.OrderWorkflowSupport.id;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.now;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.OrderStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原订单取消、支付和收货状态迁移，保持各入口原事务/鉴权/锁边界。 */
@Service
public class OrderStateServiceImpl implements OrderStateService {
  /** 原订单与月表访问实现。 */
  private final OrderSqlMapper orderSqlMapper;

  /** 详情SKU快照的原JSON配置。 */
  private final ObjectMapper mapper;

  /** 与取消/支付状态共同提交的库存动作持久层。 */
  private final OrderStockActionMapper orderStockActionMapper;

  /** 与普通创建服务共用的操作监视器。 */
  private final OrderWriteMonitor writeMonitor;

  /** 注入既有读写资源和共享监视器，不添加远程库存依赖。 */
  public OrderStateServiceImpl(
      OrderSqlMapper orderSqlMapper,
      ObjectMapper mapper,
      OrderStockActionMapper orderStockActionMapper,
      OrderWriteMonitor writeMonitor) {
    this.orderSqlMapper = orderSqlMapper;
    this.mapper = mapper;
    this.orderStockActionMapper = orderStockActionMapper;
    this.writeMonitor = writeMonitor;
  }

  /** 先读取原详情及身份，再按原待支付状态迁移并写回滚动作。 */
  @Override
  @Transactional
  public OrderVO cancel(String orderNo, LongSupplier userIdSupplier) {
    synchronized (writeMonitor) {
      // 1. 保持完整详情读取先于身份读取及归属校验。
      OrderVO order = find(orderNo);
      long userId = userIdSupplier.getAsLong();
      if (order.userId != userId) throw new BizException(ErrorCodes.FORBIDDEN, "无权访问此订单", 403);
      // 2. 仅原目标终态重放，其他状态沿原规则拒绝。
      if ("CANCELLED".equals(order.status)) return order;
      if (!"PENDING_PAYMENT".equals(order.status))
        throw new BizException(ErrorCodes.STATUS, "订单状态不允许取消", 409);
      // 3. CAS成功才写同事务动作；竞争失败读取原目标状态。
      if (!OrderStateWriter.transition(orderSqlMapper, order, "CANCELLED"))
        return completedOrConflict(orderNo, "CANCELLED");
      orderStockActionMapper.insert(id(), orderNo, "ROLLBACK", now().toLocalDateTime());
      return find(orderNo);
    }
  }

  /** 按原归属与状态证明支付，只有CAS成功才创建确认动作。 */
  @Override
  @Transactional
  public OrderVO paid(String orderNo, LongSupplier userIdSupplier) {
    synchronized (writeMonitor) {
      // 1. 在原位置读取详情及用户，核对订单归属。
      OrderVO order = owned(orderNo, userIdSupplier);
      // 2. 原已支付状态幂等返回，只有待支付可继续迁移。
      if ("PAID".equals(order.status)) return order;
      if (!"PENDING_PAYMENT".equals(order.status))
        throw new BizException(ErrorCodes.STATUS, "订单状态不允许支付", 409);
      // 3. CAS成功才写同事务动作；竞争失败读取原目标状态。
      if (!OrderStateWriter.transition(orderSqlMapper, order, "PAID"))
        return completedOrConflict(orderNo, "PAID");
      orderStockActionMapper.insert(id(), orderNo, "CONFIRM", now().toLocalDateTime());
      return find(orderNo);
    }
  }

  /** 保持原无事务/无共享锁入口，确认收货不创建库存动作。 */
  @Override
  public OrderVO confirm(String orderNo, LongSupplier userIdSupplier) {
    // 1. 读取详情后核对原用户身份。
    OrderVO order = owned(orderNo, userIdSupplier);
    // 2. 保持完成重放及已支付状态门。
    if ("COMPLETED".equals(order.status)) return order;
    if (!"PAID".equals(order.status)) throw new BizException(ErrorCodes.STATUS, "订单尚未支付", 409);
    // 3. 沿原无事务CAS更新与重读，不创建库存动作。
    if (!OrderStateWriter.transition(orderSqlMapper, order, "COMPLETED"))
      return completedOrConflict(orderNo, "COMPLETED");
    return find(orderNo);
  }

  /** 读取主表和明细后才获取当前身份，保持原失败顺序。 */
  private OrderVO owned(String orderNo, LongSupplier userIdSupplier) {
    OrderVO order = find(orderNo);
    if (!order.userId.equals(userIdSupplier.getAsLong()))
      throw new BizException(ErrorCodes.FORBIDDEN, "无权访问此订单", 403);
    return order;
  }

  /** 复用原详情读取步骤，沿调用方既有事务执行。 */
  private OrderVO find(String orderNo) {
    return OrderDetailReader.read(orderSqlMapper, mapper, orderNo);
  }

  /** 重新读取 CAS 竞争结果，仅目标状态视为幂等成功。 */
  private OrderVO completedOrConflict(String orderNo, String targetStatus) {
    OrderVO currentOrder = find(orderNo);
    if (targetStatus.equals(currentOrder.status)) return currentOrder;
    throw new BizException(ErrorCodes.STATUS, "订单状态已被其他操作更新", 409);
  }
}
