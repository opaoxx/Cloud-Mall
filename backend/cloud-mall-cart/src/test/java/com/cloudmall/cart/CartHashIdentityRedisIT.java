package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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

/** 使用专用DB15和有原子owner证明的随机Cart键验证Hash field与SKU身份及拒绝无写，不操作其他键。 */
class CartHashIdentityRedisIT {
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
    owner.addedAt = "cm17bw-owner:" + UUID.randomUUID();
    ownerJson = mapper.writeValueAsString(owner);
    DefaultRedisScript<Long> claim =
        new DefaultRedisScript<>(
            "if redis.call('EXISTS',KEYS[1])~=0 then return 0 end redis.call('HSET',KEYS[1],ARGV[1],ARGV[2]); redis.call('EXPIRE',KEYS[1],600); return 1",
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
              "if redis.call('HGET',KEYS[1],ARGV[1])~=ARGV[2] then return 0 end return redis.call('DEL',KEYS[1])",
              Long.class);
      assertEquals(1L, redis.execute(cleanup, List.of(ownedKey), OWNER_FIELD, ownerJson));
      assertFalse(Boolean.TRUE.equals(redis.hasKey(ownedKey)));
      System.out.println("CM17BW owned fixture removed from Redis DB15: " + ownedKey);
      ownedKey = null;
    }
  }

  @AfterAll
  static void disconnect() {
    if (connections != null) connections.destroy();
  }

  @Test
  void wrongFieldSkuCannotOverwriteOrDeleteAnIndependentHealthySku() throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String operation : List.of("add", "update", "checked", "all", "preview", "clear")) {
      resetSample();
      CartItemPO wrong = item(2);
      wrong.skuId = 8L;
      wrong.checked = true;
      redis.opsForHash().put(ownedKey, "7", mapper.writeValueAsString(wrong));
      collectRejected(operation, checks);
    }
    assertAll(checks);
  }

  @Test
  void aliasesMissingIdentityAndNullPoAreCorruptionAcrossSharedReaders() throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String operation : List.of("add", "update", "checked", "all", "preview", "clear")) {
      for (String variant : List.of("leading-zero", "trailing", "null-sku", "null-po")) {
        resetSample();
        CartItemPO invalid = item(2);
        invalid.checked = true;
        if ("null-sku".equals(variant)) invalid.skuId = null;
        String field =
            "leading-zero".equals(variant) ? "07" : "trailing".equals(variant) ? "7 " : "7";
        redis
            .opsForHash()
            .put(
                ownedKey,
                field,
                "null-po".equals(variant) ? "null" : mapper.writeValueAsString(invalid));
        collectRejected(operation, checks);
      }
    }
    assertAll(checks);
  }

  @Test
  void canonicalFieldsKeepNormalCrudSelectionAndAmounts() throws Exception {
    seed(2);
    CartItemVO added = (CartItemVO) service.add(request(1)).data;
    assertEquals(3, added.quantity);
    assertEquals(4, ((CartItemVO) service.update(7L, request(4)).data).quantity);
    assertTrue(((CartItemVO) service.checked(7L, Map.of("checked", true)).data).checked);
    Map<?, ?> preview = (Map<?, ?>) service.preview(Map.of("skuIds", List.of(7L))).data;
    assertEquals("49.20", preview.get("totalAmount"));
    assertEquals(
        7L,
        ((CartItemPO)
                mapper.readValue(
                    String.valueOf(redis.opsForHash().get(ownedKey, "7")), CartItemPO.class))
            .skuId);
    service.clearChecked();
    assertNull(redis.opsForHash().get(ownedKey, "7"));
    assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
  }

  @Test
  void explicitDeleteCanRemoveTheBadFieldWithoutTrustingOrDeletingItsWrongSku() throws Exception {
    resetSample();
    CartItemPO wrong = item(2);
    wrong.skuId = 8L;
    redis.opsForHash().put(ownedKey, "7", mapper.writeValueAsString(wrong));
    Object healthyBefore = redis.opsForHash().get(ownedKey, "8");
    assertEquals("0", service.delete(7L).code);
    assertNull(redis.opsForHash().get(ownedKey, "7"));
    assertEquals(healthyBefore, redis.opsForHash().get(ownedKey, "8"));
    assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
  }

  /** 同例样本只更改owner独占键的三个目标字段，独立SKU8必须始终受保护。 */
  private void resetSample() throws Exception {
    redis.opsForHash().delete(ownedKey, "7", "8", "07", "7 ");
    CartItemPO healthy = item(3);
    healthy.skuId = 8L;
    healthy.checked = false;
    redis.opsForHash().put(ownedKey, "8", mapper.writeValueAsString(healthy));
    redis.expire(ownedKey, Duration.ofSeconds(120));
    doReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(8L, 88L, "independent product", "88.00", Map.of())))
        .when(products)
        .getSku(8L);
  }

  /** 捕获原Hash/TTL和异常，旧NPE或404也不得被当成正确的数据损坏500。 */
  private void collectRejected(
      String operation, List<org.junit.jupiter.api.function.Executable> checks) {
    Map<Object, Object> before = redis.opsForHash().entries(ownedKey);
    long beforeTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
    RuntimeException failure = null;
    try {
      switch (operation) {
        case "add" -> service.add(request(1));
        case "update" -> service.update(7L, request(4));
        case "checked" -> service.checked(7L, Map.of("checked", false));
        case "all" -> service.all();
        case "preview" -> service.preview(Map.of("skuIds", List.of(7L, 8L)));
        case "clear" -> service.clearChecked();
        default -> throw new AssertionError(operation);
      }
    } catch (RuntimeException rejected) {
      failure = rejected;
    }
    RuntimeException captured = failure;
    Map<Object, Object> after = redis.opsForHash().entries(ownedKey);
    long afterTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
    checks.add(() -> assertInstanceOf(BizException.class, captured, operation));
    if (captured instanceof BizException business) {
      checks.add(() -> assertEquals(500, business.getHttpStatus(), operation));
      checks.add(() -> assertEquals(ErrorCodes.INTERNAL, business.getCode(), operation));
    }
    checks.add(() -> assertEquals(before, after, operation));
    checks.add(() -> assertTrue(afterTtl > 0 && afterTtl <= beforeTtl, operation));
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
