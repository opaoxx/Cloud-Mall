package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BalanceContractTest {
  @Test
  void userRegistrationAndDebitUseTheDefaultBalanceAndAtomicGuard() throws Exception {
    String source =
        Files.readString(
            Path.of("src/main/java/com/cloudmall/user/service/impl/UserServiceImpl.java"));
    String mapper =
        Files.readString(Path.of("src/main/java/com/cloudmall/user/mapper/UserMapper.java"));
    String schema = Files.readString(Path.of("../database/schema.sql"));
    assertTrue(source.contains("new BigDecimal(\"10000.00\")"));
    assertTrue(mapper.contains("balance=balance-#{amount}"));
    assertTrue(mapper.contains("balance>=#{amount}"));
    assertTrue(source.contains("balance:debit:"));
    assertTrue(schema.contains("balance DECIMAL(18,2) NOT NULL DEFAULT 10000.00"));
  }
}
