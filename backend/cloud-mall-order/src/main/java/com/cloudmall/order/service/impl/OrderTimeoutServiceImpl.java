package com.cloudmall.order.service.impl;

import static com.cloudmall.order.service.impl.OrderWorkflowSupport.id;
import static com.cloudmall.order.service.impl.OrderWorkflowSupport.now;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.OrderTimeoutService;
import com.cloudmall.order.util.OrderTimeoutMessageParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 超时关单的原事务、数据库期限及条件更新规则。 */
@Service
public class OrderTimeoutServiceImpl implements OrderTimeoutService {
  /** 完整订单/明细读取及状态条件更新入口。 */
  private final OrderSqlMapper orderSqlMapper;

  /** 原消息与订单快照JSON配置。 */
  private final ObjectMapper objectMapper;

  /** 与取消状态同事务落盘的库存回滚动作。 */
  private final OrderStockActionMapper orderStockActionMapper;

  /** 注入原超时流程的三个依赖，沿用既有订单数据源。 */
  public OrderTimeoutServiceImpl(
      OrderSqlMapper orderSqlMapper,
      ObjectMapper objectMapper,
      OrderStockActionMapper orderStockActionMapper) {
    this.orderSqlMapper = orderSqlMapper;
    this.objectMapper = objectMapper;
    this.orderStockActionMapper = orderStockActionMapper;
  }

  /** 在原REQUIRED事务内取body、解析并直接调用同一目标对象的关单规则。 */
  @Override
  @Transactional
  public void closeFromMessage(Message message) {
    // 1. 保留事务在读取/解码之前开启，解析错误同样沿原事务回滚。
    String orderNo = OrderTimeoutMessageParser.orderNo(objectMapper, message.getBody());
    // 2. 保留原self调用，不额外进入第二个事务代理。
    closeExpiredOrder(orderNo);
  }

  /** 按订单号处理旧版超时消息并供恢复测试复用。 */
  @Override
  @Transactional
  public void closeExpiredOrder(String orderNo) {
    // 1. 保留原空订单号与完整详情读取，不添加鉴权或共享写锁。
    if (orderNo == null || orderNo.isBlank()) return;
    OrderVO order = find(orderNo);
    // 2. 只用待支付订单的数据库期限证明到期。
    if (!"PENDING_PAYMENT".equals(order.status)) return;
    // 缺失DB期限无法证明已到期，不从消息或创建时间推导自动关单授权。
    if (order.expireAt == null) throw new BizException(ErrorCodes.STATUS, "订单超时期限缺失，暂不关单", 409);
    if (now().isBefore(OffsetDateTime.parse(order.expireAt))) {
      throw new BizException(ErrorCodes.STATUS, "订单尚未到超时关单时间", 409);
    }
    // 3. 原CAS恰1行时才在同一事务记录回滚动作。
    if (OrderStateWriter.transition(orderSqlMapper, order, "CANCELLED"))
      orderStockActionMapper.insert(id(), orderNo, "ROLLBACK", now().toLocalDateTime());
  }

  /** 复用原详情读取步骤，沿调用方既有事务执行。 */
  private OrderVO find(String orderNo) {
    return OrderDetailReader.read(orderSqlMapper, objectMapper, orderNo);
  }
}
