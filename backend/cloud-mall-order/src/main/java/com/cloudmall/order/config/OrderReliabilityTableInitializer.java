package com.cloudmall.order.config;

import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import javax.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 为已有本地数据库准备订单预扣恢复与 outbox 表。 */
@Component
public class OrderReliabilityTableInitializer {
  /** 预扣意图持久层。 */
  private final OrderReservationIntentMapper reservationIntentMapper;

  /** 订单事件 outbox 持久层。 */
  private final OrderOutboxMapper orderOutboxMapper;

  /** 创建可靠性交付建表器。 */
  public OrderReliabilityTableInitializer(
      OrderReservationIntentMapper reservationIntentMapper, OrderOutboxMapper orderOutboxMapper) {
    this.reservationIntentMapper = reservationIntentMapper;
    this.orderOutboxMapper = orderOutboxMapper;
  }

  /** 以幂等 DDL 准备两张订单可靠性表。 */
  @PostConstruct
  public void prepareTables() {
    // 1. 先准备普通订单预扣意图表。
    reservationIntentMapper.ensureTable();
    // 2. 再准备订单事件 outbox 表。
    orderOutboxMapper.ensureTable();
  }
}
