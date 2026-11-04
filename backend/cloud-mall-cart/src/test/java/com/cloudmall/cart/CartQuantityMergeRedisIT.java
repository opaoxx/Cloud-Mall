package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.cart.controller.CartController;
import com.cloudmall.cart.domain.dto.CartItemDTO;
import com.cloudmall.cart.domain.po.CartItemPO;
import com.cloudmall.cart.domain.vo.CartItemVO;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.impl.CartServiceImpl;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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

/** 使用专用DB15和有原子owner证明的随机Cart键验证数量与拒绝无写，不操作其他键。 */
class CartQuantityMergeRedisIT {
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
    owner.addedAt = "cm17bu-owner:" + UUID.randomUUID();
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
    ProductClient products = mock(ProductClient.class);
    when(products.getSku(7L))
        .thenReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(7L, 99L, "current product", "12.30", Map.of())));
    controller = new CartController(new CartServiceImpl(redis, mapper, products));
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
      System.out.println("CM17BU owned fixture removed from Redis DB15: " + ownedKey);
      ownedKey = null;
    }
  }

  @AfterAll
  static void disconnect() {
    if (connections != null) connections.destroy();
  }

  @Test
  void positiveOverflowIs400WithoutChangingHashOrExtendingTtl() throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (int[] values :
        List.of(
            new int[] {Integer.MAX_VALUE, 1},
            new int[] {Integer.MAX_VALUE - 1, 2},
            new int[] {1, Integer.MAX_VALUE},
            new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE})) {
      seed(values[0]);
      Map<Object, Object> before = redis.opsForHash().entries(ownedKey);
      long beforeTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
      BizException failure = null;
      try {
        controller.add(request(values[1]));
      } catch (BizException rejected) {
        failure = rejected;
      }
      BizException captured = failure;
      Map<Object, Object> after = redis.opsForHash().entries(ownedKey);
      long afterTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
      checks.add(() -> assertNotNull(captured));
      if (captured != null) {
        checks.add(() -> assertEquals(400, captured.getHttpStatus()));
        checks.add(() -> assertEquals(ErrorCodes.INVALID, captured.getCode()));
      }
      checks.add(() -> assertEquals(before, after));
      checks.add(() -> assertTrue(afterTtl > 0 && afterTtl <= beforeTtl, "拒绝不得续期"));
    }
    assertAll(checks);
  }

  @Test
  void nonPositiveStoredQuantityIsCorruptionAndNotARepairByAddition() throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (int stored : List.of(0, -1, Integer.MIN_VALUE)) {
      seed(stored);
      Map<Object, Object> before = redis.opsForHash().entries(ownedKey);
      long beforeTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
      BizException failure = null;
      try {
        controller.add(request(5));
      } catch (BizException rejected) {
        failure = rejected;
      }
      BizException captured = failure;
      Map<Object, Object> after = redis.opsForHash().entries(ownedKey);
      long afterTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
      checks.add(() -> assertNotNull(captured));
      if (captured != null) {
        checks.add(() -> assertEquals(500, captured.getHttpStatus()));
        checks.add(() -> assertEquals(ErrorCodes.INTERNAL, captured.getCode()));
      }
      checks.add(() -> assertEquals(before, after));
      checks.add(() -> assertTrue(afterTtl > 0 && afterTtl <= beforeTtl));
    }
    assertAll(checks);
  }

  @Test
  void legalMergeUpToIntMaxKeepsSelectionTimeAndCurrentProductFacts() throws Exception {
    for (int[] values :
        List.of(
            new int[] {1, 1},
            new int[] {Integer.MAX_VALUE - 1, 1},
            new int[] {1, Integer.MAX_VALUE - 1})) {
      seed(values[0]);
      CartItemVO result = (CartItemVO) controller.add(request(values[1])).data;
      CartItemPO saved =
          mapper.readValue(String.valueOf(redis.opsForHash().get(ownedKey, "7")), CartItemPO.class);
      assertEquals((long) values[0] + values[1], result.quantity);
      assertEquals(result.quantity, saved.quantity);
      assertEquals(99L, saved.productId);
      assertEquals("current product", saved.productName);
      assertEquals("12.30", saved.unitPrice);
      assertFalse(saved.checked);
      assertEquals("2026-10-08T12:00:00+08:00", saved.addedAt);
      assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
      assertTrue(
          redis.getExpire(ownedKey, TimeUnit.SECONDS) >= Duration.ofDays(30).getSeconds() - 10);
    }
  }

  @Test
  void firstAddAndDirectPutStillAcceptTheExistingPositiveIntRange() {
    redis.opsForHash().delete(ownedKey, "7");
    CartItemVO first = (CartItemVO) controller.add(request(Integer.MAX_VALUE)).data;
    assertEquals(Integer.MAX_VALUE, first.quantity);
    assertTrue(first.checked);
    assertEquals(ZoneOffset.ofHours(8), OffsetDateTime.parse(first.addedAt).getOffset());
    assertEquals(1, ((CartItemVO) controller.update(7L, request(1)).data).quantity);
    assertEquals(
        Integer.MAX_VALUE,
        ((CartItemVO) controller.update(7L, request(Integer.MAX_VALUE)).data).quantity);
    Map<Object, Object> before = redis.opsForHash().entries(ownedKey);
    BizException failure =
        assertThrows(BizException.class, () -> controller.update(7L, request(0)));
    assertEquals(400, failure.getHttpStatus());
    assertEquals(before, redis.opsForHash().entries(ownedKey));
    assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
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
