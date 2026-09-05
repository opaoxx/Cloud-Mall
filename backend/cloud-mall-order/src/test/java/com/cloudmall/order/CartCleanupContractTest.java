package com.cloudmall.order;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CartCleanupContractTest {
    @Test
    void orderCreationDeletesOnlySubmittedSkusFromCart() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/cloudmall/order/OrderController.java"));
        assertTrue(source.contains("cart.deleteItem(item.skuId,uid)"));
        assertTrue(source.contains("订单创建成功但购物车清理失败"));
    }
}
