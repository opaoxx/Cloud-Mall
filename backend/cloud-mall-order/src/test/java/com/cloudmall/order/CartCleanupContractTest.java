package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.order.config.FeignAuthConfiguration;
import com.cloudmall.order.domain.dto.CartCleanupSnapshotDTO;
import com.cloudmall.order.feign.CartClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestTemplate;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** 清理时序变化不得改变Cart的SKU路径或原用户Header契约。 */
class CartCleanupContractTest {
  @Test
  void cartDeletePreservesSkuPathOriginalUserHeaderAndResponseEnvelope() throws Exception {
    Method deleteItem = CartClient.class.getMethod("deleteItem", Long.class, Long.class);
    assertArrayEquals(
        new String[] {"/api/cart/items/{skuId}"},
        deleteItem.getAnnotation(DeleteMapping.class).value());
    assertEquals("skuId", deleteItem.getParameters()[0].getAnnotation(PathVariable.class).value());
    assertEquals(
        "X-User-Id", deleteItem.getParameters()[1].getAnnotation(RequestHeader.class).value());
    assertEquals(ApiResponse.class, deleteItem.getReturnType());
  }

  @Test
  void precommitSnapshotAndPostcommitCleanupKeepOriginalUserAndRawJson() throws Exception {
    Method read = CartClient.class.getMethod("cleanupSnapshot", Long.class, Long.class);
    Method cleanup =
        CartClient.class.getMethod(
            "cleanupItem", Long.class, Long.class, CartCleanupSnapshotDTO.class);
    assertArrayEquals(
        new String[] {"/api/cart/items/{skuId}/cleanup-snapshot"},
        read.getAnnotation(GetMapping.class).value());
    assertArrayEquals(
        new String[] {"/api/cart/items/{skuId}/cleanup"},
        cleanup.getAnnotation(PostMapping.class).value());
    for (Method method : new Method[] {read, cleanup}) {
      assertEquals("skuId", method.getParameters()[0].getAnnotation(PathVariable.class).value());
      assertEquals(
          "X-User-Id", method.getParameters()[1].getAnnotation(RequestHeader.class).value());
      assertEquals(ApiResponse.class, method.getReturnType());
    }
    assertNotNull(cleanup.getParameters()[2].getAnnotation(RequestBody.class));
    ObjectMapper mapper = new ObjectMapper();
    var snapshot =
        new CartCleanupSnapshotDTO(Long.MAX_VALUE, " { \"skuId\":9223372036854775807 } ");
    String json = mapper.writeValueAsString(snapshot);
    assertEquals(snapshot, mapper.readValue(json, CartCleanupSnapshotDTO.class));
    assertEquals(2, mapper.readTree(json).size());
    assertTrue(mapper.readTree(json).path("skuId").isIntegralNumber());
    assertEquals(snapshot.expectedJson(), mapper.readTree(json).path("expectedJson").textValue());
  }

  @Test
  void explicitOriginalUserHeaderIsNotMixedWithTheLateAuthenticationContext() {
    try {
      AuthContext.set(99L, "ADMIN");
      RequestTemplate explicit = new RequestTemplate();
      explicit.header("x-user-id", "7");
      new FeignAuthConfiguration().authHeaders().apply(explicit);
      var headers =
          explicit.headers().entrySet().stream()
              .filter(entry -> entry.getKey().equalsIgnoreCase("X-User-Id"))
              .findFirst()
              .orElseThrow();
      assertEquals(List.of("7"), List.copyOf(headers.getValue()));
      assertTrue(
          explicit.headers().keySet().stream()
              .noneMatch(header -> header.equalsIgnoreCase("X-User-Role")));
      RequestTemplate explicitRole = new RequestTemplate();
      explicitRole.header("X-User-Id", "7");
      explicitRole.header("x-user-role", "USER");
      new FeignAuthConfiguration().authHeaders().apply(explicitRole);
      assertEquals(List.of("7"), List.copyOf(explicitRole.headers().get("X-User-Id")));
      assertEquals(
          List.of("USER"),
          List.copyOf(
              explicitRole.headers().entrySet().stream()
                  .filter(entry -> entry.getKey().equalsIgnoreCase("X-User-Role"))
                  .findFirst()
                  .orElseThrow()
                  .getValue()));
      RequestTemplate implicit = new RequestTemplate();
      new FeignAuthConfiguration().authHeaders().apply(implicit);
      assertEquals(List.of("99"), List.copyOf(implicit.headers().get("X-User-Id")));
      assertEquals(List.of("ADMIN"), List.copyOf(implicit.headers().get("X-User-Role")));
      AuthContext.clear();
      RequestTemplate anonymous = new RequestTemplate();
      new FeignAuthConfiguration().authHeaders().apply(anonymous);
      assertTrue(anonymous.headers().isEmpty());
    } finally {
      AuthContext.clear();
    }
  }
}
