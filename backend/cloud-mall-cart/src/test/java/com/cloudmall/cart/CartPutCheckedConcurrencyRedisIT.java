package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.cart.controller.CartController;
import com.cloudmall.cart.domain.dto.CartItemDTO;
import com.cloudmall.cart.domain.po.CartItemPO;
import com.cloudmall.cart.domain.vo.CartItemVO;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.impl.CartServiceImpl;
import com.cloudmall.cart.service.impl.CartStorage;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/** 使用专用DB15和有原子owner证明的随机Cart键验证PUT/checked不覆盖其他字段及不复活删除，不操作其他键。 */
class CartPutCheckedConcurrencyRedisIT {
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
  private CartController controller;

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
    owner.addedAt = "cm17ca-owner:" + UUID.randomUUID();
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
    controller = new CartController(new CartServiceImpl(new CartStorage(redis, mapper), products));
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
      System.out.println("CM17CA owned fixture removed from Redis DB15: " + ownedKey);
      ownedKey = null;
    }
  }

  @AfterAll
  static void disconnect() {
    if (connections != null) connections.destroy();
  }

  @Test
  void concurrentAbsoluteQuantityAndSelectionUpdatesPreserveBothIntents() throws Exception {
    seed(2);
    race(false);
    assertEquals(7, saved().quantity);
    assertTrue(saved().checked);
    verifyNoInteractions(products);
  }

  @Test
  void checkedAfterAnAppliedAdditionCannotResetTheNewQuantity() throws Exception {
    seed(2);
    race(true);
    assertEquals(5, saved().quantity);
    assertTrue(saved().checked);
    verify(products).getSku(7L);
  }

  @Test
  void deleteAfterReadNeverGetsResurrectedByEitherMutation() throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String operation : List.of("update", "checked")) {
      seed(2);
      StringRedisTemplate observed = spy(redis);
      @SuppressWarnings("unchecked")
      HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
      doReturn(hashes).when(observed).opsForHash();
      AtomicInteger reads = new AtomicInteger();
      when(hashes.entries(ownedKey))
          .thenAnswer(
              invocation -> {
                Map<Object, Object> snapshot = redis.opsForHash().entries(ownedKey);
                if (reads.incrementAndGet() == 1) redis.opsForHash().delete(ownedKey, "7");
                return snapshot;
              });
      doAnswer(
              invocation -> {
                redis
                    .opsForHash()
                    .put(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2));
                return null;
              })
          .when(hashes)
          .put(anyString(), any(), any());
      long beforeTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
      BizException failure = null;
      try {
        CartController target =
            new CartController(new CartServiceImpl(new CartStorage(observed, mapper), products));
        if ("update".equals(operation)) target.update(7L, request(4));
        else target.checked(7L, Map.of("checked", true));
      } catch (BizException rejected) {
        failure = rejected;
      }
      BizException captured = failure;
      Object after = redis.opsForHash().get(ownedKey, "7");
      long afterTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
      checks.add(() -> assertNotNull(captured, operation));
      if (captured != null)
        checks.add(() -> assertEquals(404, captured.getHttpStatus(), operation));
      checks.add(() -> assertNull(after, operation));
      checks.add(() -> assertTrue(afterTtl > 0 && afterTtl <= beforeTtl, operation));
      assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
    }
    assertAll(checks);
    verifyNoInteractions(products);
  }

  @Test
  void exactJsonMetadataAndOptionalCheckedSemanticsStayUnchanged() throws Exception {
    String raw =
        " { \"skuId\":7, \"quantity\":2, \"productId\":9223372036854775807, \"checked\":false,"
            + " \"unitPrice\":\"0012.30\", \"productName\":\"original\","
            + " \"addedAt\":\"2026-10-08T12:00:00+08:00\" } ";
    redis.opsForHash().put(ownedKey, "7", raw);
    CartItemDTO change = request(7);
    change.checked = null;
    assertFalse(((CartItemVO) controller.update(7L, change).data).checked);
    assertTrue(((CartItemVO) controller.checked(7L, Map.of("checked", true)).data).checked);
    change.quantity = 4;
    change.checked = false;
    assertFalse(((CartItemVO) controller.update(7L, change).data).checked);
    assertEquals(4, saved().quantity);
    assertEquals(Long.MAX_VALUE, saved().productId);
    assertEquals("0012.30", saved().unitPrice);
    assertEquals("original", saved().productName);
    assertEquals("2026-10-08T12:00:00+08:00", saved().addedAt);
    verifyNoInteractions(products);
  }

  /** 初读强制同快照，add场景强制追加先成功，再允许勾选写入以暴露旧数量回退。 */
  private void race(boolean withAdd) throws Exception {
    CyclicBarrier firstReads = new CyclicBarrier(2);
    CountDownLatch addApplied = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    ThreadLocal<Boolean> addWorker = new ThreadLocal<>();
    StringRedisTemplate observed = spy(redis);
    @SuppressWarnings("unchecked")
    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    doReturn(hashes).when(observed).opsForHash();
    when(hashes.entries(ownedKey))
        .thenAnswer(
            invocation -> {
              Map<Object, Object> snapshot = redis.opsForHash().entries(ownedKey);
              if (reads.incrementAndGet() <= 2) firstReads.await(10, TimeUnit.SECONDS);
              return snapshot;
            });
    doAnswer(
            invocation -> {
              if (withAdd && !Boolean.TRUE.equals(addWorker.get()))
                assertTrue(addApplied.await(10, TimeUnit.SECONDS));
              redis
                  .opsForHash()
                  .put(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2));
              return null;
            })
        .when(hashes)
        .put(anyString(), any(), any());
    doAnswer(
            invocation -> {
              if (withAdd && !Boolean.TRUE.equals(addWorker.get()))
                assertTrue(addApplied.await(10, TimeUnit.SECONDS));
              Object result = invocation.callRealMethod();
              if (Boolean.TRUE.equals(addWorker.get()) && Long.valueOf(1).equals(result))
                addApplied.countDown();
              return result;
            })
        .when(observed)
        .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
    long userId = Long.parseLong(ownedKey.substring("cart:".length()));
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Future<Object> quantity =
          workers.submit(
              () -> {
                AuthContext.set(userId, "USER");
                addWorker.set(withAdd);
                try {
                  CartController target =
                      new CartController(
                          new CartServiceImpl(new CartStorage(observed, mapper), products));
                  CartItemDTO requested = request(withAdd ? 3 : 7);
                  requested.checked = null;
                  return withAdd ? target.add(requested).data : target.update(7L, requested).data;
                } finally {
                  AuthContext.clear();
                  addWorker.remove();
                }
              });
      Future<Object> selection =
          workers.submit(
              () -> {
                AuthContext.set(userId, "USER");
                addWorker.set(false);
                try {
                  return new CartController(
                          new CartServiceImpl(new CartStorage(observed, mapper), products))
                      .checked(7L, Map.of("checked", true))
                      .data;
                } finally {
                  AuthContext.clear();
                  addWorker.remove();
                }
              });
      assertInstanceOf(CartItemVO.class, quantity.get(20, TimeUnit.SECONDS));
      assertInstanceOf(CartItemVO.class, selection.get(20, TimeUnit.SECONDS));
    } finally {
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /** 直接读取原始项，避免GET刷新再次改动观察结果。 */
  private CartItemPO saved() throws Exception {
    return mapper.readValue(
        String.valueOf(redis.opsForHash().get(ownedKey, "7")), CartItemPO.class);
  }

  /** 只准备当前owner独占键中的目标SKU和短TTL，方便发现非法合并续期。 */
  private void seed(int quantity) throws Exception {
    redis.opsForHash().put(ownedKey, "7", mapper.writeValueAsString(item(quantity)));
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
