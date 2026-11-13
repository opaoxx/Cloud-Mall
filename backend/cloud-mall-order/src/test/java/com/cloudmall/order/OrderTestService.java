package com.cloudmall.order;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.order.controller.OrderSeckillAdmissionController;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.dto.SeckillOrderDTO;
import com.cloudmall.order.feign.*;
import com.cloudmall.order.mapper.*;
import com.cloudmall.order.service.impl.OrderCreationServiceImpl;
import com.cloudmall.order.service.impl.OrderReservationIntentService;
import com.cloudmall.order.service.impl.OrderSeckillAdmissionServiceImpl;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.cloudmall.order.service.impl.OrderStateServiceImpl;
import com.cloudmall.order.service.impl.OrderWriteMonitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/** 旧混合算法矩阵的测试装配，创建调用真实实现；不注册Spring Bean、不进入生产Jar。 旧夹具的外层事务保留在此测试入口，实际生产代理与锁顺序由直接装配生产类的测试验证。 */
public class OrderTestService extends OrderServiceImpl {
  /** 旧混合夹具的Stock依赖，继续允许历史测试替换。 */
  @Autowired private StockClient stock;

  /** 旧混合夹具的User依赖，继续允许历史测试替换。 */
  @Autowired private UserClient users;

  /** 旧手动装配夹具的创建/状态入口共用监视器。 */
  private OrderWriteMonitor writeMonitor = new OrderWriteMonitor();

  /** 原夹具的Cart依赖。 */
  private CartClient cart;

  /** 原夹具可替换的创建时钟。 */
  private Clock creationClock = Clock.system(ZoneOffset.ofHours(8));

  /** 原夹具的独立意图事务服务。 */
  private OrderReservationIntentService reservationIntentService;

  /** 主事务内的意图Mapper。 */
  private OrderReservationIntentMapper reservationIntentMapper;

  /** 保留旧手动装配测试入口。 */
  public OrderTestService() {}

  /** 同一组依赖同时供原剩余流程和实际创建实现使用。 */
  public OrderTestService(
      StockClient stock,
      ProductClient products,
      UserClient users,
      CartClient cart,
      OrderSqlMapper orders,
      ObjectMapper mapper) {
    super(products, orders, mapper);
    this.stock = stock;
    this.users = users;
    this.cart = cart;
  }

  /** 原矩阵的事务边界和响应形状；所有业务都交由真实创建实现。 */
  @Transactional
  public ApiResponse<?> create(String key, CreateOrderDTO request) {
    var creation =
        new OrderCreationServiceImpl(
            field("stock", StockClient.class),
            field("products", ProductClient.class),
            field("users", UserClient.class),
            cart,
            field("orderSqlMapper", OrderSqlMapper.class),
            field("mapper", ObjectMapper.class),
            reservationIntentService,
            field("orderOutboxMapper", OrderOutboxMapper.class),
            reservationIntentMapper,
            field("writeMonitor", OrderWriteMonitor.class));
    ReflectionTestUtils.setField(creation, "creationClock", creationClock);
    return ApiResponse.ok(creation.create(key, request, AuthContext::requireUserId));
  }

  /** 秒杀旧夹具调用真实Controller和受理实现，不复制算法或添加事务。 */
  public ApiResponse<?> seckill(String key, SeckillOrderDTO request) {
    return new OrderSeckillAdmissionController(
            new OrderSeckillAdmissionServiceImpl(stock, users, field("mapper", ObjectMapper.class)))
        .seckill(key, request);
  }

  private <T> T field(String name, Class<T> type) {
    return type.cast(ReflectionTestUtils.getField(this, name));
  }

  /** 保留原混合矩阵外层事务，业务委托实际状态实现。 */
  @Transactional
  public ApiResponse<?> cancel(String orderNo, Long headerUser) {
    return ApiResponse.ok(
        state()
            .cancel(orderNo, headerUser == null ? AuthContext::requireUserId : () -> headerUser));
  }

  /** 原支付算法矩阵通过实际状态实现运行。 */
  @Transactional
  public ApiResponse<?> paid(String orderNo) {
    return ApiResponse.ok(state().paid(orderNo, AuthContext::requireUserId));
  }

  /** 测试适配也不为收货确认新建事务或锁。 */
  public ApiResponse<?> confirm(String orderNo) {
    return ApiResponse.ok(state().confirm(orderNo, AuthContext::requireUserId));
  }

  private OrderStateServiceImpl state() {
    return new OrderStateServiceImpl(
        field("orderSqlMapper", OrderSqlMapper.class),
        field("mapper", ObjectMapper.class),
        field("orderStockActionMapper", OrderStockActionMapper.class),
        writeMonitor);
  }
}
