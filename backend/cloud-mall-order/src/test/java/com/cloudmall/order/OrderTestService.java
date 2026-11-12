package com.cloudmall.order;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.feign.*;
import com.cloudmall.order.mapper.*;
import com.cloudmall.order.service.impl.OrderCreationServiceImpl;
import com.cloudmall.order.service.impl.OrderReservationIntentService;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.cloudmall.order.service.impl.OrderWriteMonitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/** 旧混合算法矩阵的测试装配，创建调用真实实现；不注册Spring Bean、不进入生产Jar。 旧夹具的外层事务保留在此测试入口，实际生产代理与锁顺序由直接装配生产类的测试验证。 */
public class OrderTestService extends OrderServiceImpl {
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
    super(stock, products, users, orders, mapper);
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

  private <T> T field(String name, Class<T> type) {
    return type.cast(ReflectionTestUtils.getField(this, name));
  }
}
