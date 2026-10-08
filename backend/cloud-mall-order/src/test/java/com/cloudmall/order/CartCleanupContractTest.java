package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.feign.CartClient;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
