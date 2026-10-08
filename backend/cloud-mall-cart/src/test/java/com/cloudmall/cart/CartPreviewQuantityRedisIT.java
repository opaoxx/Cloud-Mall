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

/** 使用专用DB15和有原子owner证明的随机Cart键验证已选数量预检与拒绝无写，不操作其他键。 */
class CartPreviewQuantityRedisIT {
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
    owner.addedAt = "cm17bx-owner:" + UUID.randomUUID();
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
      System.out.println("CM17BX owned fixture removed from Redis DB15: " + ownedKey);
      ownedKey = null;
    }
  }

  @AfterAll
  static void disconnect() {
    if (connections != null) connections.destroy();
  }

  @Test
  void selectedNonPositiveQuantitiesNeverReachProductWriteOrAmounts() throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (int quantity : List.of(0, -1, Integer.MIN_VALUE)) {
      for (String selection : List.of("explicit", "checked")) {
        prepare(quantity, true);
        clearInvocations(products);
        Map<Object, Object> before = redis.opsForHash().entries(ownedKey);
        long beforeTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
        BizException failure = null;
        try {
          service.preview("explicit".equals(selection) ? Map.of("skuIds", List.of(7L)) : Map.of());
        } catch (BizException rejected) {
          failure = rejected;
        }
        BizException captured = failure;
        Map<Object, Object> after = redis.opsForHash().entries(ownedKey);
        long afterTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
        int productCalls = mockingDetails(products).getInvocations().size();
        checks.add(() -> assertNotNull(captured, selection + "/" + quantity));
        if (captured != null) {
          checks.add(() -> assertEquals(500, captured.getHttpStatus()));
          checks.add(() -> assertEquals(ErrorCodes.INTERNAL, captured.getCode()));
        }
        checks.add(() -> assertEquals(before, after));
        checks.add(() -> assertTrue(afterTtl > 0 && afterTtl <= beforeTtl));
        checks.add(() -> assertEquals(0, productCalls));
      }
    }
    assertAll(checks);
  }

  @Test
  void preflightChecksAllSelectedItemsBeforeRefreshingEvenAHealthyOne() throws Exception {
    prepare(2, true);
    CartItemPO corrupt = item(0);
    corrupt.skuId = 8L;
    corrupt.checked = true;
    redis.opsForHash().put(ownedKey, "8", mapper.writeValueAsString(corrupt));
    doReturn(ApiResponse.ok(new ProductClient.SkuView(8L, 88L, "product8", "88.00", Map.of())))
        .when(products)
        .getSku(8L);
    Map<Object, Object> before = redis.opsForHash().entries(ownedKey);
    long beforeTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
    clearInvocations(products);
    BizException failure = null;
    try {
      service.preview(Map.of("skuIds", List.of(7L, 8L)));
    } catch (BizException rejected) {
      failure = rejected;
    }
    BizException captured = failure;
    Map<Object, Object> after = redis.opsForHash().entries(ownedKey);
    long afterTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
    assertAll(
        () -> assertNotNull(captured),
        () -> assertEquals(before, after),
        () -> assertTrue(afterTtl > 0 && afterTtl <= beforeTtl),
        () -> verifyNoInteractions(products));
    assertEquals(500, captured.getHttpStatus());
    assertEquals(ErrorCodes.INTERNAL, captured.getCode());
  }

  @Test
  void unselectedCorruptionDoesNotBlockValidSubtotalAndPutCanRepairIt() throws Exception {
    prepare(2, true);
    CartItemPO unselected = item(0);
    unselected.skuId = 8L;
    unselected.checked = false;
    redis.opsForHash().put(ownedKey, "8", mapper.writeValueAsString(unselected));
    Object originalBadRow = redis.opsForHash().get(ownedKey, "8");
    Map<?, ?> explicit = (Map<?, ?>) service.preview(Map.of("skuIds", List.of(7L))).data;
    assertEquals("24.60", explicit.get("totalAmount"));
    Map<?, ?> defaultChecked = (Map<?, ?>) service.preview(Map.of()).data;
    assertEquals("24.60", defaultChecked.get("payAmount"));
    assertEquals(originalBadRow, redis.opsForHash().get(ownedKey, "8"));
    verify(products, never()).getSku(8L);
    CartItemDTO repair = request(3);
    repair.skuId = 8L;
    assertEquals(3, ((CartItemVO) service.update(8L, repair).data).quantity);
    doReturn(ApiResponse.ok(new ProductClient.SkuView(8L, 88L, "product8", "5.00", Map.of())))
        .when(products)
        .getSku(8L);
    Map<?, ?> repaired = (Map<?, ?>) service.preview(Map.of("skuIds", List.of(8L))).data;
    assertEquals("15.00", repaired.get("totalAmount"));
    assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
  }

  @Test
  void maximumPositiveQuantityAndEmptyCheckedSetKeepExistingAmountStrings() throws Exception {
    prepare(Integer.MAX_VALUE, true);
    Map<?, ?> maximum = (Map<?, ?>) service.preview(Map.of("skuIds", List.of(7L))).data;
    assertEquals("26414048858.10", maximum.get("totalAmount"));
    assertEquals("26414048858.10", maximum.get("payAmount"));
    prepare(0, false);
    clearInvocations(products);
    Map<Object, Object> before = redis.opsForHash().entries(ownedKey);
    Map<?, ?> empty = (Map<?, ?>) service.preview(Map.of()).data;
    assertEquals("0", empty.get("totalAmount"));
    assertEquals("0", empty.get("payAmount"));
    assertTrue(((List<?>) empty.get("items")).isEmpty());
    assertEquals(before, redis.opsForHash().entries(ownedKey));
    verifyNoInteractions(products);
  }

  /** 本例原始数量/勾选只在owned键中构造，owner字段始终合法且未勾选。 */
  private void prepare(int quantity, boolean checked) throws Exception {
    CartItemPO selected = item(quantity);
    selected.checked = checked;
    redis.opsForHash().put(ownedKey, "7", mapper.writeValueAsString(selected));
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
