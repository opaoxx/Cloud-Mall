package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.cart.controller.CartController;
import com.cloudmall.cart.domain.dto.CartItemDTO;
import com.cloudmall.cart.domain.po.CartItemPO;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/** 使用专用DB15和有原子owner证明的随机Cart键验证clearChecked不按旧快照误删并发更新，不操作其他键。 */
class CartCheckedCompareDeleteRedisIT {
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
    owner.addedAt = "cm17cb-owner:" + UUID.randomUUID();
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
      System.out.println("CM17CB owned fixture removed from Redis DB15: " + ownedKey);
      ownedKey = null;
    }
  }

  @AfterAll
  static void disconnect() {
    if (connections != null) connections.destroy();
  }

  @Test
  void concurrentCartMutationsSurviveAnOlderCheckedCleanup() throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String operation : List.of("unchecked", "update", "add", "readd")) {
      seedChecked();
      AtomicReference<String> latestJson = new AtomicReference<>();
      AtomicLong latestTtl = new AtomicLong();
      StringRedisTemplate observed =
          afterSnapshot(
              () -> {
                switch (operation) {
                  case "unchecked" -> controller.checked(7L, Map.of("checked", false));
                  case "update" -> controller.update(7L, request(9));
                  case "add" -> controller.add(request(3));
                  case "readd" -> {
                    controller.delete(7L);
                    controller.add(request(4));
                  }
                  default -> throw new AssertionError(operation);
                }
                latestJson.set(String.valueOf(redis.opsForHash().get(ownedKey, "7")));
                latestTtl.set(redis.getExpire(ownedKey, TimeUnit.MILLISECONDS));
              });
      ApiResponse<?> response =
          new CartController(new CartServiceImpl(new CartStorage(observed, mapper), products))
              .clearChecked();
      Object after = redis.opsForHash().get(ownedKey, "7");
      long afterTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
      checks.add(() -> assertEquals("0", response.code, operation));
      checks.add(() -> assertNull(response.data, operation));
      checks.add(() -> assertEquals(latestJson.get(), after, operation));
      checks.add(() -> assertTrue(afterTtl > 0 && afterTtl <= latestTtl.get(), operation));
      assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
    }
    assertAll(checks);
    verify(products, times(2)).getSku(7L);
    verifyNoMoreInteractions(products);
  }

  @Test
  void unchangedCheckedItemsAreDeletedAndOtherJsonAndTtlStayUntouched() throws Exception {
    String raw =
        " { \"skuId\":7, \"quantity\":2, \"checked\":true,"
            + " \"productId\":9223372036854775807, \"unitPrice\":\"0012.30\","
            + " \"productName\":null, \"addedAt\":\"2026-10-08T12:00:00+08:00\" } ";
    String unchecked =
        " { \"skuId\":8, \"quantity\":9, \"checked\":null, \"unitPrice\":\"7.80\" } ";
    redis.opsForHash().put(ownedKey, "7", raw);
    redis.opsForHash().put(ownedKey, "8", unchecked);
    redis.expire(ownedKey, Duration.ofSeconds(120));
    long beforeTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
    assertEquals("0", controller.clearChecked().code);
    assertNull(redis.opsForHash().get(ownedKey, "7"));
    assertEquals(unchecked, redis.opsForHash().get(ownedKey, "8"));
    assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
    assertNull(controller.clearChecked().data);
    assertEquals(unchecked, redis.opsForHash().get(ownedKey, "8"));
    assertTrue(redis.getExpire(ownedKey, TimeUnit.MILLISECONDS) <= beforeTtl);
    verifyNoInteractions(products);
  }

  @Test
  void anAlreadyDeletedTargetIsANoOpWithoutRenewingTtl() throws Exception {
    seedChecked();
    StringRedisTemplate observed = afterSnapshot(() -> controller.delete(7L));
    long beforeTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
    assertNull(
        new CartController(new CartServiceImpl(new CartStorage(observed, mapper), products))
            .clearChecked()
            .data);
    assertNull(redis.opsForHash().get(ownedKey, "7"));
    assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
    assertTrue(redis.getExpire(ownedKey, TimeUnit.MILLISECONDS) <= beforeTtl);
    verifyNoInteractions(products);
  }

  @Test
  void aLostReplyAfterTheRealDeleteDoesNotRepeatTheScript() throws Exception {
    seedChecked();
    StringRedisTemplate observed = spy(redis);
    AtomicInteger scripts = new AtomicInteger();
    doAnswer(
            invocation -> {
              scripts.incrementAndGet();
              assertEquals(1L, invocation.callRealMethod());
              return null;
            })
        .when(observed)
        .execute(any(RedisScript.class), anyList(), any(), any());
    long beforeTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
    BizException failure =
        assertThrows(
            BizException.class,
            () ->
                new CartController(new CartServiceImpl(new CartStorage(observed, mapper), products))
                    .clearChecked());
    assertEquals(500, failure.getHttpStatus());
    assertEquals(1, scripts.get());
    assertNull(redis.opsForHash().get(ownedKey, "7"));
    assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
    assertTrue(redis.getExpire(ownedKey, TimeUnit.MILLISECONDS) <= beforeTtl);
    verifyNoInteractions(products);
  }

  /** 在首次真实HGETALL之后、旧清理开始之前执行另一Cart入口，确定控制交错顺序。 */
  private StringRedisTemplate afterSnapshot(Runnable mutation) {
    StringRedisTemplate observed = spy(redis);
    @SuppressWarnings("unchecked")
    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    doReturn(hashes).when(observed).opsForHash();
    AtomicInteger reads = new AtomicInteger();
    when(hashes.entries(ownedKey))
        .thenAnswer(
            invocation -> {
              Map<Object, Object> snapshot = redis.opsForHash().entries(ownedKey);
              if (reads.incrementAndGet() == 1) mutation.run();
              return snapshot;
            });
    // 旧实现仍必须真实HDEL，红灯不能来自一个没有执行删除的替身。
    doAnswer(
            invocation -> {
              String targetKey = invocation.getArgument(0);
              Object targetField = invocation.getArgument(1);
              return redis.opsForHash().delete(targetKey, targetField);
            })
        .when(hashes)
        .delete(anyString(), any());
    return observed;
  }

  /** 当前owner独占键中的目标checked=true，短TTL用于发现非法续期。 */
  private void seedChecked() throws Exception {
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
