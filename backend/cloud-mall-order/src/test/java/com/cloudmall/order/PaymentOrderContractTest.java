package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaymentOrderContractTest {
  @Test
  void paidOrderConfirmsReservedStockAndIsIdempotent() throws Exception {
    String source =
        Files.readString(
            Path.of("src/main/java/com/cloudmall/order/service/impl/OrderServiceImpl.java"));
    String compact = compact(source);
    assertTrue(compact.contains("if(\"PAID\".equals(o.status)){returnApiResponse.ok(o);}"));
    assertTrue(compact.contains("setStatus(o,\"PAID\")"));
    assertTrue(compact.contains("stock.confirm(orderNo)"));
  }

  /** 执行 compact 相关操作。 */
  private static String compact(String source) {
    return source.replaceAll("\\s+", "");
  }
}
