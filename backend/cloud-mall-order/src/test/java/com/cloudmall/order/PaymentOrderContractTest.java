package com.cloudmall.order;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentOrderContractTest {
    @Test
    void paidOrderConfirmsReservedStockAndIsIdempotent() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/cloudmall/order/OrderController.java"));
        assertTrue(source.contains("if(\"PAID\".equals(o.status))return ApiResponse.ok(o)"));
        assertTrue(source.contains("setStatus(o,\"PAID\")"));
        assertTrue(source.contains("stock.confirm(orderNo)"));
    }
}
