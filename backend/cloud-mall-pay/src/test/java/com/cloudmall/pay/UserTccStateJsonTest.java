package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** User账本和身份观察仅接受各自原生JSON类型，保字段级解析与既有Java调用契约。 */
class UserTccStateJsonTest {
  /** 两个身份字段只接受1..Long.MAX_VALUE原生整数，不能以强转获得原身份。 */
  @Test
  void onlyNativePositiveLongTokensAreUserIdentities() throws Exception {
    var mapper = new ObjectMapper();
    for (long identity : List.of(1L, 7L, 31L, Long.MAX_VALUE)) {
      var value =
          mapper.readValue(
              "{\"userId\":" + identity + ",\"branchId\":" + identity + "}", UserTccStateVO.class);
      assertEquals(identity, value.userId);
      assertEquals(identity, value.branchId);
    }
    for (String token :
        List.of(
            "0",
            "-1",
            "-9223372036854775809",
            "9223372036854775808",
            "18446744073709551623",
            "7.0",
            "7.9",
            "7e0",
            "\"7\"",
            "\"31\"",
            "\"\"",
            "true",
            "false",
            "null",
            "[]",
            "[7]",
            "{}",
            "{\"value\":7}")) {
      var value =
          mapper.readValue(
              "{\"userId\":" + token + ",\"branchId\":" + token + ",\"ledgerApplied\":false}",
              UserTccStateVO.class);
      assertNull(value.userId, token);
      assertNull(value.branchId, token);
      assertTrue(value.ledgerAppliedPresent, token);
      assertFalse(value.ledgerApplied, token);
    }
    var missing = mapper.readValue("{}", UserTccStateVO.class);
    assertNull(missing.userId);
    assertNull(missing.branchId);
  }

  /** 无效身份嵌套值消费完整，另一个身份和后续业务字段保留。 */
  @Test
  void nestedIdentityValuesDoNotConsumeSiblingEvidence() throws Exception {
    var mapper = new ObjectMapper();
    for (String token : List.of("[7,{\"nested\":[31]}]", "{\"value\":[7,{\"deep\":31}]}")) {
      var invalidUser =
          mapper.readValue(
              "{\"userId\":"
                  + token
                  + ",\"branchId\":31,\"ledgerApplied\":false,\"state\":\"CANCELLED\"}",
              UserTccStateVO.class);
      assertNull(invalidUser.userId);
      assertEquals(31L, invalidUser.branchId);
      assertTrue(invalidUser.ledgerAppliedPresent);
      assertFalse(invalidUser.ledgerApplied);
      assertEquals("CANCELLED", invalidUser.state);
      var invalidBranch =
          mapper.readValue(
              "{\"branchId\":"
                  + token
                  + ",\"userId\":7,\"ledgerApplied\":false,\"state\":\"TRYING\",\"amount\":\"10.00\"}",
              UserTccStateVO.class);
      assertNull(invalidBranch.branchId);
      assertEquals(7L, invalidBranch.userId);
      assertTrue(invalidBranch.ledgerAppliedPresent);
      assertFalse(invalidBranch.ledgerApplied);
      assertEquals("TRYING", invalidBranch.state);
      assertEquals("10.00", invalidBranch.amount);
    }
  }

  /** public Long赋值及整数输出保持，同mapper的普通Long强转规则不变。 */
  @Test
  void identityAssignmentAndSerializationKeepTheirContract() throws Exception {
    var mapper = new ObjectMapper();
    var value = new UserTccStateVO();
    value.userId = 7L;
    value.branchId = 31L;
    var json = mapper.readTree(mapper.writeValueAsString(value));
    assertTrue(json.get("userId").isIntegralNumber());
    assertEquals(7L, json.get("userId").longValue());
    assertTrue(json.get("branchId").isIntegralNumber());
    assertEquals(31L, json.get("branchId").longValue());
    value.userId = null;
    value.branchId = null;
    var nullJson = mapper.readTree(mapper.writeValueAsString(value));
    assertTrue(nullJson.get("userId").isNull());
    assertTrue(nullJson.get("branchId").isNull());
    assertEquals(7L, mapper.readValue("7.9", Long.class));
    assertEquals(31L, mapper.readValue("\"31\"", Long.class));
  }

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
