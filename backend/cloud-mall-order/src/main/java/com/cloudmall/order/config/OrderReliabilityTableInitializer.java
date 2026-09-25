package com.cloudmall.order.config;

import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import javax.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 为已有本地数据库准备订单预扣恢复与 outbox 表。 */
@Component
public class OrderReliabilityTableInitializer {
  /** 预扣意图持久层。 */
  private final OrderReservationIntentMapper reservationIntentMapper;

  /** 订单事件 outbox 持久层。 */
  private final OrderOutboxMapper orderOutboxMapper;

  /** 支付或关单后的库存动作持久层。 */
  private final OrderStockActionMapper orderStockActionMapper;

  /** 创建可靠性交付建表器。 */
  public OrderReliabilityTableInitializer(
      OrderReservationIntentMapper reservationIntentMapper,
      OrderOutboxMapper orderOutboxMapper,
      OrderStockActionMapper orderStockActionMapper) {
    this.reservationIntentMapper = reservationIntentMapper;
    this.orderOutboxMapper = orderOutboxMapper;
    this.orderStockActionMapper = orderStockActionMapper;
  }

  /** 以幂等 DDL 准备两张订单可靠性表。 */
  @PostConstruct
  public void prepareTables() {
    // 1. 先准备普通订单预扣意图表。
    reservationIntentMapper.ensureTable();
    // 2. 再准备订单事件 outbox 表。
    orderOutboxMapper.ensureTable();
    // 3. 准备订单终态后的库存动作恢复表。
    orderStockActionMapper.ensureTable();
  }
}
