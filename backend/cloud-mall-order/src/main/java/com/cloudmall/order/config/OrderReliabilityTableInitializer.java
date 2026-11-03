package com.cloudmall.order.config;

import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.mapper.SeckillOrderDedupMapper;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** 为已有本地数据库准备订单预扣恢复与 outbox 表。 */
@Component
@DependsOn("orderRoutingAudit")
public class OrderReliabilityTableInitializer {
  /** 预扣意图持久层。 */
  private final OrderReservationIntentMapper reservationIntentMapper;

  /** 订单事件 outbox 持久层。 */
  private final OrderOutboxMapper orderOutboxMapper;

  /** 支付或关单后的库存动作持久层。 */
  private final OrderStockActionMapper orderStockActionMapper;

  private final SeckillOrderDedupMapper seckillOrderDedupMapper;

  private final SeckillMessageRecoveryMapper seckillMessageRecoveryMapper;

  /** Spring-only mode; null retains the original direct-construction contract. */
  private Environment routingEnvironment;
  /** Optional only for dynamic routing; explicit mode requires a completed bootstrap. */
  private ObjectProvider<OrderShardingBootstrap> bootstrapProvider;

  /** Connects Spring lifecycle selection while retaining the original constructor. */
  @Autowired
  public OrderReliabilityTableInitializer(OrderReservationIntentMapper intents, OrderOutboxMapper outbox,
      OrderStockActionMapper actions, SeckillOrderDedupMapper dedup, SeckillMessageRecoveryMapper recovery,
      Environment environment, ObjectProvider<OrderShardingBootstrap> provider) {
    this(intents, outbox, actions, dedup, recovery);
    this.routingEnvironment = environment;
    this.bootstrapProvider = provider;
  }

  /** 创建可靠性交付建表器。 */
  public OrderReliabilityTableInitializer(
      OrderReservationIntentMapper reservationIntentMapper,
      OrderOutboxMapper orderOutboxMapper,
      OrderStockActionMapper orderStockActionMapper,
      SeckillOrderDedupMapper seckillOrderDedupMapper,
      SeckillMessageRecoveryMapper seckillMessageRecoveryMapper) {
    this.reservationIntentMapper = reservationIntentMapper;
    this.orderOutboxMapper = orderOutboxMapper;
    this.orderStockActionMapper = orderStockActionMapper;
    this.seckillOrderDedupMapper = seckillOrderDedupMapper;
    this.seckillMessageRecoveryMapper = seckillMessageRecoveryMapper;
  }

  /** 以幂等 DDL 准备两张订单可靠性表。 */
  @PostConstruct
  public void prepareTables() {
    if (explicitBootstrap() != null) return;
    // 1. 先准备普通订单预扣意图表。
    reservationIntentMapper.ensureTable();
    // 2. 再准备订单事件 outbox 表。
    orderOutboxMapper.ensureTable();
    // 3. 准备订单终态后的库存动作恢复表。
    orderStockActionMapper.ensureTable();
    seckillOrderDedupMapper.ensureTable();
    seckillMessageRecoveryMapper.ensureTable();
  }

  /** New mode cannot silently fall back to business-connection DDL. */
  private OrderShardingBootstrap explicitBootstrap() {
    if (routingEnvironment == null || !OrderRoutingConfiguration.SHARDING_MONTHLY.equals(OrderRoutingConfiguration.mode(routingEnvironment))) return null;
    OrderShardingBootstrap bootstrap = bootstrapProvider == null ? null : bootstrapProvider.getIfAvailable();
    if (bootstrap == null) throw new IllegalStateException("ORDER_SHARD_BOOTSTRAP_NOT_READY");
    bootstrap.requireReady();
    return bootstrap;
  }
}
