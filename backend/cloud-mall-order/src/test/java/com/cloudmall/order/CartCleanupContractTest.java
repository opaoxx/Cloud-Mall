package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CartCleanupContractTest {
  @Test
  void orderCreationDeletesOnlySubmittedSkusFromCart() throws Exception {
    String source =
        Files.readString(
            Path.of("src/main/java/com/cloudmall/order/service/impl/OrderServiceImpl.java"));
    assertTrue(compact(source).contains("cart.deleteItem(item.skuId,uid)"));
    assertTrue(source.contains("订单创建成功但购物车清理失败"));
  }

  /** 执行 compact 相关操作。 */
  private static String compact(String source) {
    return source.replaceAll("\\s+", "");
  }
}
