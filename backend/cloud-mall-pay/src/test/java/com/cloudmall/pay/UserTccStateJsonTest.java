package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** User账本观察只接受JSON原生布尔，保字段级解析与既有Java调用契约。 */
class UserTccStateJsonTest {
  /** 非原生布尔、缺字段和null不能因Jackson标量强转获得presence。 */
  @Test
  void onlyNativeBooleanTokensArePresentLedgerEvidence() throws Exception {
    var mapper = new ObjectMapper();
    for (boolean applied : List.of(false, true)) {
      var value = mapper.readValue("{\"ledgerApplied\":" + applied + "}", UserTccStateVO.class);
      assertTrue(value.ledgerAppliedPresent);
      assertEquals(applied, value.ledgerApplied);
    }
    for (String token :
        List.of(
            "0",
            "1",
            "-1",
            "0.0",
            "1e0",
            "\"false\"",
            "\"true\"",
            "\"FALSE\"",
            "\" true \"",
            "\"\"",
            "\"other\"",
            "null",
            "[]",
            "[false]",
            "{}",
            "{\"value\":false}")) {
      var value = mapper.readValue("{\"ledgerApplied\":" + token + "}", UserTccStateVO.class);
      assertFalse(value.ledgerAppliedPresent, token);
      assertFalse(value.ledgerApplied, token);
    }
    var missing = mapper.readValue("{}", UserTccStateVO.class);
    assertFalse(missing.ledgerAppliedPresent);
    assertFalse(missing.ledgerApplied);
  }

  /** 原public Boolean setter与序列化保持，JSON不能伪造内部presence。 */
  @Test
  void programmaticSetterAndSerializedBooleanKeepTheirContract() throws Exception {
    var mapper = new ObjectMapper();
    var value = new UserTccStateVO();
    for (boolean applied : List.of(false, true)) {
      value.setLedgerApplied(applied);
      assertTrue(value.ledgerAppliedPresent);
      assertEquals(applied, value.ledgerApplied);
      var json = mapper.readTree(mapper.writeValueAsString(value));
      assertTrue(json.get("ledgerApplied").isBoolean());
      assertEquals(applied, json.get("ledgerApplied").booleanValue());
      assertFalse(json.has("ledgerAppliedPresent"));
    }
    value.setLedgerApplied(null);
    assertFalse(value.ledgerAppliedPresent);
    assertFalse(value.ledgerApplied);
    var spoofed =
        mapper.readValue(
            "{\"ledgerApplied\":\"false\",\"ledgerAppliedPresent\":true}", UserTccStateVO.class);
    assertFalse(spoofed.ledgerAppliedPresent);
    assertFalse(spoofed.ledgerApplied);
  }

  /** 非布尔嵌套值消费完整，后续字段与同mapper全局Boolean绑定保持。 */
  @Test
  void nestedValuesDoNotConsumeSiblingFieldsOrChangeGlobalCoercion() throws Exception {
    var mapper = new ObjectMapper();
    for (String token :
        List.of("[false,{\"nested\":[true]}]", "{\"value\":[false,{\"deep\":true}]}")) {
      var value =
          mapper.readValue(
              "{\"ledgerApplied\":"
                  + token
                  + ",\"state\":\"CANCELLED\",\"payNo\":\"pay-json\",\"userId\":7,\"branchId\":31}",
              UserTccStateVO.class);
      assertFalse(value.ledgerAppliedPresent);
      assertFalse(value.ledgerApplied);
      assertEquals("CANCELLED", value.state);
      assertEquals("pay-json", value.payNo);
      assertEquals(7L, value.userId);
      assertEquals(31L, value.branchId);
    }
    assertEquals(Boolean.TRUE, mapper.readValue("1", Boolean.class));
    assertEquals(Boolean.FALSE, mapper.readValue("\"false\"", Boolean.class));
  }
}
