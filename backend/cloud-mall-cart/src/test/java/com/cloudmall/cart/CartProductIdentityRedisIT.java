package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.cart.controller.CartController;
import com.cloudmall.cart.domain.dto.CartItemDTO;
import com.cloudmall.cart.domain.dto.CartSelectionDTO;
import com.cloudmall.cart.domain.dto.ProductSkuDTO;
import com.cloudmall.cart.domain.po.CartItemPO;
import com.cloudmall.cart.domain.vo.CartItemVO;
import com.cloudmall.cart.domain.vo.CartSettlementPreviewVO;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.impl.CartServiceImpl;
import com.cloudmall.cart.service.impl.CartStorage;
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

/** 使用专用DB15和有原子owner证明的随机Cart键验证商品响应身份与拒绝无写，不操作其他键。 */
class CartProductIdentityRedisIT {
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
    owner.addedAt = "cm17bv-owner:" + UUID.randomUUID();
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
            ApiResponse.ok(new ProductSkuDTO(7L, 99L, "current product", "12.30", Map.of())));
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
      System.out.println("CM17BV owned fixture removed from Redis DB15: " + ownedKey);
      ownedKey = null;
    }
  }

  @AfterAll
  static void disconnect() {
    if (connections != null) connections.destroy();
  }

  @Test
  void wrongIdentityOrErrorEnvelopeCannotCreateNewCartFacts() throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (ApiResponse<ProductSkuDTO> response : badResponses()) {
      redis.opsForHash().delete(ownedKey, "7");
      redis.expire(ownedKey, Duration.ofSeconds(120));
      doReturn(response).when(products).getSku(7L);
      collectRejected("new", checks);
    }
    assertAll(checks);
  }

  @Test
  void untrustedProductCannotRewriteExistingAddRefreshOrSettlementPrice() throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String operation : List.of("add", "all", "preview")) {
      for (ApiResponse<ProductSkuDTO> response : badResponses()) {
        seed(2);
        doReturn(response).when(products).getSku(7L);
        collectRejected(operation, checks);
      }
    }
    assertAll(checks);
  }

  @Test
  void exactSuccessKeepsCurrentFactsQuantitiesAndSettlementAmount() throws Exception {
    seed(2);
    doReturn(ApiResponse.ok(new ProductSkuDTO(7L, 99L, "current product", "12.30", Map.of())))
        .when(products)
        .getSku(7L);
    CartItemVO added = (CartItemVO) controller.add(request(1)).data;
    assertEquals(3, added.quantity);
    assertEquals(99L, added.productId);
    assertEquals("current product", added.productName);
    assertEquals("12.30", added.unitPrice);
    assertFalse(added.checked);
    assertEquals("2026-10-08T12:00:00+08:00", added.addedAt);
    List<?> listed = (List<?>) controller.all().data;
    CartItemVO refreshed =
        (CartItemVO)
            listed.stream()
                .filter(value -> Long.valueOf(7L).equals(((CartItemVO) value).skuId))
                .findFirst()
                .orElseThrow();
    assertEquals(3, refreshed.quantity);
    assertEquals("12.30", refreshed.unitPrice);
    CartSettlementPreviewVO preview =
        (CartSettlementPreviewVO)
            controller.preview(new CartSelectionDTO(Map.of("skuIds", List.of(7L)))).data;
    assertEquals("36.90", preview.totalAmount());
    assertEquals("36.90", preview.payAmount());
    assertTrue(((List<?>) preview.invalidItems()).isEmpty());
    assertEquals(ownerJson, redis.opsForHash().get(ownedKey, OWNER_FIELD));
  }

  @Test
  void oldMissingEnvelopeOrDataIsStill404WithoutAnyWrite() throws Exception {
    List<ApiResponse<ProductSkuDTO>> missing = new ArrayList<>();
    missing.add(null);
    missing.add(ApiResponse.ok(null));
    missing.add(ApiResponse.error(ErrorCodes.NOT_FOUND, "not found"));
    for (ApiResponse<ProductSkuDTO> response : missing) {
      seed(2);
      Map<Object, Object> before = redis.opsForHash().entries(ownedKey);
      doReturn(response).when(products).getSku(7L);
      BizException failure = assertThrows(BizException.class, () -> controller.add(request(1)));
      assertEquals(404, failure.getHttpStatus());
      assertEquals(ErrorCodes.NOT_FOUND, failure.getCode());
      assertEquals(before, redis.opsForHash().entries(ownedKey));
    }
  }

  /** 本样本只检查失败SKU，不把多SKU刷新当成有回滚能力的事务。 */
  private void collectRejected(
      String operation, List<org.junit.jupiter.api.function.Executable> checks) {
    Map<Object, Object> before = redis.opsForHash().entries(ownedKey);
    long beforeTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
    BizException failure = null;
    try {
      switch (operation) {
        case "new", "add" -> controller.add(request(1));
        case "all" -> controller.all();
        case "preview" -> controller.preview(new CartSelectionDTO(Map.of("skuIds", List.of(7L))));
        default -> throw new AssertionError(operation);
      }
    } catch (BizException rejected) {
      failure = rejected;
    }
    BizException captured = failure;
    Map<Object, Object> after = redis.opsForHash().entries(ownedKey);
    long afterTtl = redis.getExpire(ownedKey, TimeUnit.MILLISECONDS);
    checks.add(() -> assertNotNull(captured, operation));
    if (captured != null) {
      checks.add(() -> assertEquals(500, captured.getHttpStatus(), operation));
      checks.add(() -> assertEquals(ErrorCodes.INTERNAL, captured.getCode(), operation));
    }
    checks.add(() -> assertEquals(before, after, operation));
    checks.add(() -> assertTrue(afterTtl > 0 && afterTtl <= beforeTtl, operation));
  }

  /** 数据可反序列化不等于合法商品证明：错ID/nullID/坏envelope均不得提供价格。 */
  private static List<ApiResponse<ProductSkuDTO>> badResponses() {
    List<ApiResponse<ProductSkuDTO>> responses = new ArrayList<>();
    responses.add(ApiResponse.ok(new ProductSkuDTO(8L, 999L, "wrong product", "999.00", Map.of())));
    responses.add(
        ApiResponse.ok(new ProductSkuDTO(null, 999L, "wrong product", "999.00", Map.of())));
    for (String code :
        java.util.Arrays.asList(null, "", " ", "COMMON_INTERNAL_ERROR", "COMMON_NOT_FOUND", "00")) {
      ApiResponse<ProductSkuDTO> response =
          ApiResponse.ok(new ProductSkuDTO(7L, 999L, "wrong product", "999.00", Map.of()));
      response.code = code;
      responses.add(response);
    }
    return responses;
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
