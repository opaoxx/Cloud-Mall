package com.cloudmall.pay;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BalancePaymentContractTest {
    @Test
    void successfulPaymentDebitsBalanceBeforeSynchronizingOrder() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/cloudmall/pay/PayController.java"));
        assertTrue(source.contains("users.debit"));
        assertTrue(source.contains("new UserClient.DebitRequest(p.payNo, p.amount)"));
        assertTrue(source.contains("orders.paid(p.orderNo, p.userId)"));
        assertTrue(source.contains("PAYMENT_FAILED"));
        assertTrue(source.contains("where pay_no=? for update"));
        assertTrue(source.contains("if (\"SUCCESS\".equals(p.status)) return ApiResponse.ok(p)"));
    }
}
