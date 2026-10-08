package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.cart.domain.dto.CartItemDTO;
import com.cloudmall.cart.domain.po.CartItemPO;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.impl.CartServiceImpl;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/** 使用专用DB15和有原子owner证明的随机Cart键验证摘要刷新不覆盖用户更新或复活删除项，不操作其他键。 */
class CartRefreshCompareWriteRedisIT {
  /** 仅为本测试创建的Redis连接工厂。 */
  private static LettuceConnectionFactory connections;

  /** 本测试DB15的真实Redis入口。 */
  private static StringRedisTemplate redis;

  /** 原Redis JSON映射器。 */
  private final ObjectMapper mapper = new ObjectMapper();

  /** 当前例原子独占创建的Cart键。 */
  private String ownedKey;

  /** owner字段保留合法PO结构，真实Cart读取仍可正常解析整Hash。 */
  private String ownerJson;

  /** 唯一测试owner字段，业务样本只使用SKU7。 */
  private static final String OWNER_FIELD = String.valueOf(Long.MAX_VALUE);

  /** 实际被测Cart编排。 */
  private CartServiceImpl service;

  /** 商品查询响应替身，库存/商品数据库不在本例验证范围。 */
  private ProductClient products;

  @BeforeAll
  static void connect() {
    String endpoint = System.getProperty("cloudmall.redis.test.port");
    if (endpoint == null) throw new IllegalStateException("显式IT需cloudmall.redis.test.port");
    RedisStandaloneConfiguration configuration =
        new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(endpoint));
    configuration.setDatabase(15);
    configuration.setPassword(RedisPassword.of("root"));
    connections = new LettuceConnectionFactory(configuration);
    connections.afterPropertiesSet();
    redis = new StringRedisTemplate(connections);
    redis.afterPropertiesSet();
    org.springframework.data.redis.connection.RedisConnection pingConnection =
        connections.getConnection();
    try {
      assertEquals("PONG", pingConnection.ping());
    } finally {
      pingConnection.close();
    }
  }

  @BeforeEach
  void claimOwnedKey() throws Exception {
    CartItemPO owner = item(1);
    owner.skuId = Long.MAX_VALUE;
    owner.addedAt = "cm17cc-owner:" + UUID.randomUUID();
    ownerJson = mapper.writeValueAsString(owner);
    DefaultRedisScript<Long> claim =
        new DefaultRedisScript<>(
            "if redis.call('EXISTS',KEYS[1])~=0 then return 0 end"
                + " redis.call('HSET',KEYS[1],ARGV[1],ARGV[2]); redis.call('EXPIRE',KEYS[1],600);"
                + " return 1",
            Long.class);
    boolean claimed = false;
    for (int attempt = 0; attempt < 10; attempt++) {
      long userId =
          8_000_000_000_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000_000L);
      String candidateKey = "cart:" + userId;
      if (Long.valueOf(1)
          .equals(redis.execute(claim, List.of(candidateKey), OWNER_FIELD, ownerJson))) {
        ownedKey = candidateKey;
        AuthContext.set(userId, "USER");
        claimed = true;
        break;
      }
    }
    assertTrue(claimed, "未取得独占随机fixture，不接触既有Cart");
    products = mock(ProductClient.class);
    when(products.getSku(7L))
        .thenReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(7L, 99L, "current product", "12.30", Map.of())));
    service = new CartServiceImpl(redis, mapper, products);
  }

  @AfterEach
  void removeOwnedKey() {
    AuthContext.clear();
    if (ownedKey != null) {
      DefaultRedisScript<Long> cleanup =
          new DefaultRedisScript<>(
              "if redis.call('HGET',KEYS[1],ARGV[1])~=ARGV[2] then return 0 end return"
                  + " redis.call('DEL',KEYS[1])",
              Long.class);
      assertEquals(1L, redis.execute(cleanup, List.of(ownedKey), OWNER_FIELD, ownerJson));
      assertFalse(Boolean.TRUE.equals(redis.hasKey(ownedKey)));
      System.out.println("CM17CC owned fixture removed from Redis DB15: " + ownedKey);
      ownedKey = null;
    }
  }

  @AfterAll
  static void disconnect() {
    if (connections != null) connections.destroy();
  }

  @Test
  void refreshNeverOverwritesConcurrentQuantitySelectionOrAddition() throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String operation : List.of("all", "preview")) {
      for (String change : List.of("quantity", "unchecked", "add")) {
        seed();
        AtomicReference<Object> latest = new AtomicReference<>();
        AtomicLong latestTtl = new AtomicLong();
        duringProductRead(
            () -> {
              switch (change) {
                case "quantity" -> service.update(7L, request(9));
                case "unchecked" -> service.checked(7L, Map.of("checked", false));
                case "add" -> service.add(request(3));
                default -> throw new AssertionError(change);
              }
              latest.set(redis.opsForHash().get(ownedKey, "7"));
              latestTtl.set(redis.getExpire(ownedKey, TimeUnit.MILLISECONDS));
            });
        BizException failure = refreshFailure(operation, service);
        Object saved = redis.opsForHash().get(ownedKey, "7");
        long afterTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
        String label = operation + "/" + change;
        checks.add(() -> assertNotNull(failure, label));
        if (failure != null) {
          checks.add(() -> assertEquals(409, failure.getHttpStatus(), label));
          checks.add(() -> assertEquals(ErrorCodes.CONFLICT, failure.getCode(), label));
        }
        checks.add(() -> assertEquals(latest.get(), saved, label));
        checks.add(() -> assertTrue(afterTtl > 0 && afterTtl <= latestTtl.get(), label));
        assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
      }
    }
    assertAll(checks);
  }

  @Test
  void refreshCannotResurrectDeletedItemsOrReplaceANewlyAddedItem() throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String operation : List.of("all", "preview")) {
      for (String change : List.of("delete", "readd")) {
        seed();
        AtomicReference<Object> latest = new AtomicReference<>();
        AtomicLong latestTtl = new AtomicLong();
        duringProductRead(
            () -> {
              service.delete(7L);
              if ("readd".equals(change)) service.add(request(4));
              latest.set(redis.opsForHash().get(ownedKey, "7"));
              latestTtl.set(redis.getExpire(ownedKey, TimeUnit.MILLISECONDS));
            });
        BizException failure = refreshFailure(operation, service);
        Object saved = redis.opsForHash().get(ownedKey, "7");
        long afterTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
        String label = operation + "/" + change;
        checks.add(() -> assertNotNull(failure, label));
        if (failure != null) checks.add(() -> assertEquals(409, failure.getHttpStatus(), label));
        checks.add(() -> assertEquals(latest.get(), saved, label));
        checks.add(() -> assertTrue(afterTtl > 0 && afterTtl <= latestTtl.get(), label));
        assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
      }
    }
    assertAll(checks);
  }

  @Test
  void unchangedSnapshotsKeepSelectionQuantityTimeAndExactDecimalRepresentation() throws Exception {
    for (String operation : List.of("all", "preview")) {
      String raw =
          " { \"skuId\":7, \"quantity\":2, \"checked\":true,"
              + " \"productId\":11, \"unitPrice\":\"1.00\", \"productName\":null,"
              + " \"addedAt\":\"2026-10-08T12:00:00+08:00\" } ";
      redis.opsForHash().put(ownedKey, "7", raw);
      redis.expire(ownedKey, Duration.ofSeconds(120));
      doReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(
                      7L, Long.MAX_VALUE, "current product", "0012.30", Map.of())))
          .when(products)
          .getSku(7L);
      var response = invokeRefresh(operation, service);
      if ("preview".equals(operation)) {
        Map<?, ?> preview = (Map<?, ?>) response.data;
        assertEquals("24.60", preview.get("totalAmount"));
        assertEquals("24.60", preview.get("payAmount"));
        assertEquals(1, ((List<?>) preview.get("items")).size());
        assertTrue(((List<?>) preview.get("invalidItems")).isEmpty());
      } else {
        assertEquals(2, ((List<?>) response.data).size());
      }
      CartItemPO saved =
          mapper.readValue(String.valueOf(redis.opsForHash().get(ownedKey, "7")), CartItemPO.class);
      assertEquals(2, saved.quantity);
      assertTrue(saved.checked);
      assertEquals(Long.MAX_VALUE, saved.productId);
      assertEquals("0012.30", saved.unitPrice);
      assertEquals("current product", saved.productName);
      assertEquals("2026-10-08T12:00:00+08:00", saved.addedAt);
      assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
      assertTrue(redis.getExpire(ownedKey, TimeUnit.MILLISECONDS) > Duration.ofDays(29).toMillis());
    }
  }

  @Test
  void aLostReplyAfterAnAppliedRefreshNeverRepeatsTheWrite() throws Exception {
    for (String operation : List.of("all", "preview")) {
      seed();
      doReturn(currentSku()).when(products).getSku(7L);
      StringRedisTemplate observed = spy(redis);
      AtomicInteger scripts = new AtomicInteger();
      doAnswer(
              invocation -> {
                scripts.incrementAndGet();
                assertEquals(1L, invocation.callRealMethod());
                return null;
              })
          .when(observed)
          .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
      BizException failure =
          refreshFailure(operation, new CartServiceImpl(observed, mapper, products));
      assertNotNull(failure);
      assertEquals(500, failure.getHttpStatus());
      assertEquals(1, scripts.get());
      CartItemPO saved =
          mapper.readValue(String.valueOf(redis.opsForHash().get(ownedKey, "7")), CartItemPO.class);
      assertEquals(2, saved.quantity);
      assertTrue(saved.checked);
      assertEquals("12.30", saved.unitPrice);
      assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
    }
  }

  /** Product事实读取期间控制另一个Cart入口交错；追加的嵌套Product查询不再触发交错。 */
  private void duringProductRead(Runnable mutation) {
    AtomicBoolean changed = new AtomicBoolean();
    doAnswer(
            invocation -> {
              if (changed.compareAndSet(false, true)) mutation.run();
              return currentSku();
            })
        .when(products)
        .getSku(7L);
  }

  /** 合法当前商品摘要，真实Product数据库/HTTP不在本验证范围。 */
  private static ApiResponse<ProductClient.SkuView> currentSku() {
    return ApiResponse.ok(new ProductClient.SkuView(7L, 99L, "current product", "12.30", Map.of()));
  }

  /** 保持两个公共入口真实执行，不通过购物车GET再次改变观察结果。 */
  private ApiResponse<?> invokeRefresh(String operation, CartServiceImpl target) {
    return "all".equals(operation) ? target.all() : target.preview(Map.of("skuIds", List.of(7L)));
  }

  /** 只捕获业务失败，底层异常/测试错误不能冒充预期的409。 */
  private BizException refreshFailure(String operation, CartServiceImpl target) {
    try {
      invokeRefresh(operation, target);
      return null;
    } catch (BizException failure) {
      return failure;
    }
  }

  /** 只准备当前owner独占键的选中SKU，短TTL用来识别错误的续期。 */
  private void seed() throws Exception {
    CartItemPO original = item(2);
    original.checked = true;
    redis.opsForHash().put(ownedKey, "7", mapper.writeValueAsString(original));
    redis.expire(ownedKey, Duration.ofSeconds(120));
  }

  /** 原始摘要、选择和时间在拒绝时均必须保持原字节。 */
  private static CartItemPO item(int quantity) {
    CartItemPO item = new CartItemPO();
    item.skuId = 7L;
    item.productId = 11L;
    item.productName = "original product";
    item.unitPrice = "1.00";
    item.quantity = quantity;
    item.checked = false;
    item.addedAt = "2026-10-08T12:00:00+08:00";
    return item;
  }

  /** 客户端摘要不可替代商品服务事实；数量仍为原int传输类型。 */
  private static CartItemDTO request(int quantity) {
    CartItemDTO request = new CartItemDTO();
    request.skuId = 7L;
    request.quantity = quantity;
    request.productName = "untrusted product";
    request.unitPrice = "0.01";
    return request;
  }
}
