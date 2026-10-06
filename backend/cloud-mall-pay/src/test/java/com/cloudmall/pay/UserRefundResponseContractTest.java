package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudmall.pay.feign.UserClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 真实 User 写响应中，退款键和扣款键的 JSON 字段名不同。 */
class UserRefundResponseContractTest {
  @Test
  void acceptsBothActualRefundAndDebitResponseFields() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    UserClient.BalanceView refund =
        mapper.readValue(
            "{\"refundOperationKey\":\"refund:pay-1\",\"state\":\"APPLIED\",\"balance\":\"100.00\"}",
            UserClient.BalanceView.class);
    UserClient.BalanceView debit =
        mapper.readValue(
            "{\"operationKey\":\"pay-1\",\"state\":\"APPLIED\",\"balance\":\"90.00\"}",
            UserClient.BalanceView.class);
    assertEquals("refund:pay-1", refund.operationKey());
    assertEquals("pay-1", debit.operationKey());
    assertEquals("APPLIED", refund.state());
  }
}
