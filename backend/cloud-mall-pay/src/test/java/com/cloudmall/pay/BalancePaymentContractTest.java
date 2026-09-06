package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BalancePaymentContractTest {
  @Test
  void successfulPaymentDebitsBalanceBeforeSynchronizingOrder() throws Exception {
    String source =
        Files.readString(Path.of("src/main/java/com/cloudmall/pay/controller/PayController.java"));
    String compact = compact(source);
    assertTrue(source.contains("users.debit"));
    assertTrue(compact.contains("newUserClient.DebitRequest(p.payNo,p.amount)"));
    assertTrue(compact.contains("orders.paid(p.orderNo,p.userId)"));
    assertTrue(source.contains("PAYMENT_FAILED"));
    assertTrue(source.contains("where pay_no=?"));
    assertTrue(source.contains("for update"));
    assertTrue(compact.contains("if(\"SUCCESS\".equals(p.status)){returnApiResponse.ok(p);}"));
  }

  private static String compact(String source) {
    return source.replaceAll("\\s+", "");
  }
}
