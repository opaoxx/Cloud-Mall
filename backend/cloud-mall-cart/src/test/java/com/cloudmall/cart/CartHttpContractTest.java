package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudmall.cart.controller.CartController;
import com.cloudmall.cart.domain.dto.CartCleanupSnapshotDTO;
import com.cloudmall.cart.domain.po.CartItemPO;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.CartService;
import com.cloudmall.cart.service.impl.CartServiceImpl;
import com.cloudmall.cart.service.impl.CartStorage;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.config.CommonWebConfiguration;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 通过真实 MVC、鉴权拦截器和 Service Bean 验证分层迁移后的 HTTP 契约。 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CartHttpContractTest.HttpConfiguration.class)
@WebAppConfiguration
class CartHttpContractTest {
  /** 包含真实控制器、Service 与 Web 配置的上下文。 */
  @Autowired private WebApplicationContext context;

  /** Redis 网络边界替身，实际业务与序列化走真实实现。 */
  @Autowired private StringRedisTemplate redis;

  /** 商品远端查询替身。 */
  @Autowired private ProductClient productClient;

  /** 按业务接口注入的真实购物车服务。 */
  @Autowired private CartService cartService;

  /** 用于准备原持久化 JSON 的映射器。 */
  @Autowired private ObjectMapper objectMapper;

  /** 当前用例的 Hash 操作替身。 */
  private HashOperations<String, Object, Object> hashes;

  /** 使用完整 WebApplicationContext 的 MVC 请求入口。 */
  private MockMvc mvc;

  /** 当前请求读取的持久化项原文。 */
  private String originalJson;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void prepareRequestBoundary() throws Exception {
    reset(redis, productClient);
    AuthContext.clear();
    hashes = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(hashes);
    CartItemPO item = new CartItemPO();
    item.skuId = 7L;
    item.productId = 70L;
    item.productName = "原商品";
    item.unitPrice = "12.30";
    item.quantity = 2;
    item.checked = false;
    item.addedAt = "2026-11-04T12:00:00+08:00";
    originalJson = objectMapper.writeValueAsString(item);
    when(hashes.entries("cart:33")).thenReturn(Map.of("7", originalJson));
    when(productClient.getSku(7L))
        .thenReturn(ApiResponse.ok(new ProductClient.SkuView(7L, 70L, "当前商品", "12.30", Map.of())));
    when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
        .thenReturn(1L);
    when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @AfterEach
  void clearRequestIdentity() {
    AuthContext.clear();
  }

  @Test
  void itemRoutesPreservePayloadDefaultsAndExplicitNullResponses() throws Exception {
    assertSame(context.getBean(CartServiceImpl.class), cartService);
    mvc.perform(get("/api/cart").header("X-User-Id", "33"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("0"))
        .andExpect(jsonPath("$.data[0].unitPrice").value("12.30"))
        .andExpect(jsonPath("$.data[0].addedAt").value("2026-11-04T12:00:00+08:00"));
    mvc.perform(
            post("/api/cart/items")
                .header("X-User-Id", "33")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":7}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.quantity").value(3));
    mvc.perform(
            put("/api/cart/items/7")
                .header("X-User-Id", "33")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":4}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.quantity").value(4))
        .andExpect(jsonPath("$.data.checked").value(true));
    mvc.perform(delete("/api/cart/items/7").header("X-User-Id", "33"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    assertNull(AuthContext.userId());
  }

  @Test
  void checkedAndPreviewRoutesPreserveMissingNullAndSelectionSemantics() throws Exception {
    for (String body : new String[] {"{}", "{\"checked\":null}", "{\"checked\":false}"}) {
      mvc.perform(
              put("/api/cart/items/7/checked")
                  .header("X-User-Id", "33")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.checked").value(false));
    }
    mvc.perform(
            post("/api/cart/settlement/preview")
                .header("X-User-Id", "33")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuIds\":[7,7]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.invalidItems.length()").value(0))
        .andExpect(jsonPath("$.data.totalAmount").value("24.60"))
        .andExpect(jsonPath("$.data.payAmount").value("24.60"));
    mvc.perform(
            post("/api/cart/settlement/preview")
                .header("X-User-Id", "33")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalAmount").value("0"));
    mvc.perform(
            post("/api/cart/settlement/preview")
                .header("X-User-Id", "33")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuIds\":null}"))
        .andExpect(status().isInternalServerError());
    mvc.perform(delete("/api/cart/checked-items").header("X-User-Id", "33"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void cleanupRoutesPreserveRawSnapshotAndBooleanOutcome() throws Exception {
    mvc.perform(get("/api/cart/items/7/cleanup-snapshot").header("X-User-Id", "33"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.expectedJson").value(originalJson));
    mvc.perform(get("/api/cart/items/8/cleanup-snapshot").header("X-User-Id", "33"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    String body = objectMapper.writeValueAsString(new CartCleanupSnapshotDTO(7L, originalJson));
    mvc.perform(
            post("/api/cart/items/7/cleanup")
                .header("X-User-Id", "33")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(true));
    mvc.perform(
            post("/api/cart/items/8/cleanup")
                .header("X-User-Id", "33")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(productClient);
  }

  @Test
  void invalidProductIsSerializedWithItsOriginalErrorFields() throws Exception {
    when(productClient.getSku(7L)).thenReturn(ApiResponse.ok(null));
    mvc.perform(
            post("/api/cart/settlement/preview")
                .header("X-User-Id", "33")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuIds\":[7]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.invalidItems[0].skuId").value(7))
        .andExpect(jsonPath("$.data.invalidItems[0].code").value("COMMON_NOT_FOUND"))
        .andExpect(jsonPath("$.data.invalidItems[0].message").value("商品或SKU不存在"))
        .andExpect(jsonPath("$.data.totalAmount").value("0"));
  }

  @Test
  void unauthenticatedRequestsAndRedisFailuresKeepTheirHttpStatus() throws Exception {
    mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
    verifyNoInteractions(redis, productClient);
    when(hashes.entries("cart:33")).thenThrow(new RedisConnectionFailureException("unavailable"));
    mvc.perform(get("/api/cart").header("X-User-Id", "33"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Cache-Control", "no-store"));
    assertNull(AuthContext.userId());
  }

  @Test
  void serviceUsesExplicitOwnerInsteadOfThreadIdentity() {
    AuthContext.set(33L, "USER");
    when(hashes.entries("cart:44")).thenReturn(Map.of());
    cartService.listItems(44L);
    verify(hashes).entries("cart:44");
    verify(hashes, never()).entries("cart:33");
  }

  /** 仅替换远端边界，HTTP 绑定和业务 Bean 使用真实实现。 */
  @Configuration
  @EnableWebMvc
  @Import({
    CartController.class,
    CartServiceImpl.class,
    CartStorage.class,
    CommonWebConfiguration.class,
    GlobalExceptionHandler.class
  })
  static class HttpConfiguration {
    /** 提供 Redis 连接边界替身。 */
    @Bean
    public StringRedisTemplate redis() {
      return mock(StringRedisTemplate.class);
    }

    /** 提供商品客户端替身。 */
    @Bean
    public ProductClient productClient() {
      return mock(ProductClient.class);
    }

    /** 提供与原实现一致的 JSON 映射器。 */
    @Bean
    public ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
