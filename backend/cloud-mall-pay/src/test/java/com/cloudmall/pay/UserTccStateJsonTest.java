package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.exc.IgnoredPropertyException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** User账本、身份和文本观察仅接受各自原生JSON类型，保字段级解析与既有Java调用契约。 */
class UserTccStateJsonTest {
  /** User协议内的七个文本字段，不包含Long身份或布尔账本。 */
  private static final List<String> TEXT_FIELDS =
      List.of("payNo", "orderNo", "amount", "operationKey", "ledgerKey", "xid", "state");

  /** 十个已知根字段的重复不能以等值、末次合法值或escaped名称恢复可信。 */
  @Test
  void duplicateKnownFieldsNeverProduceTrustedState() throws Exception {
    var mapper = new ObjectMapper();
    String payload =
        """
        {"userId":7,"payNo":"pay-1","orderNo":"order-1","amount":"10.00",
         "operationKey":"tcc-debit:pay-1","ledgerKey":"pay-1","xid":"original-xid",
         "branchId":31,"state":"CANCELLED","ledgerApplied":false}
        """
            .strip();
    var original = mapper.readTree(payload);
    var fields = new ArrayList<>(TEXT_FIELDS);
    fields.addAll(List.of("userId", "branchId", "ledgerApplied"));
    for (String field : fields) {
      for (String first :
          List.of(
              mapper.writeValueAsString(original.get(field)),
              "\"other\"",
              "null",
              "{\"nested\":[0]}")) {
        String duplicate =
            "{" + mapper.writeValueAsString(field) + ":" + first + "," + payload.substring(1);
        assertNull(mapper.readValue(duplicate, UserTccStateVO.class), field + ":" + first);
      }
      String lastNull =
          payload.substring(0, payload.lastIndexOf('}'))
              + ","
              + mapper.writeValueAsString(field)
              + ":null}";
      assertNull(mapper.readValue(lastNull, UserTccStateVO.class), field);
      String escapedName =
          "\\u" + String.format("%04x", (int) field.charAt(0)) + field.substring(1);
      String escapedDuplicate =
          "{\""
              + escapedName
              + "\":"
              + mapper.writeValueAsString(original.get(field))
              + ","
              + payload.substring(1);
      assertNull(mapper.readValue(escapedDuplicate, UserTccStateVO.class), field);
    }
  }

  /** 未知/内部字段遵守原Jackson策略，其嵌套内容和重复不污染根证据去重。 */
  @Test
  void unknownAndIgnoredFieldsKeepTheirJacksonPolicies() throws Exception {
    var mapper = new ObjectMapper();
    assertThrows(
        UnrecognizedPropertyException.class,
        () -> mapper.readValue("{\"extra\":{\"userId\":8}}", UserTccStateVO.class));
    var loose = mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    var value =
        loose.readValue(
            "{\"extra\":{\"userId\":8},\"extra\":[{\"ledgerApplied\":true}],\"userId\":7,\"branchId\":31,\"ledgerApplied\":false}",
            UserTccStateVO.class);
    assertEquals(7L, value.userId);
    assertEquals(31L, value.branchId);
    assertTrue(value.ledgerAppliedPresent);
    assertFalse(value.ledgerApplied);
    var ignored =
        mapper.readValue(
            "{\"ledgerAppliedPresent\":true,\"ledgerAppliedPresent\":false,\"ledgerApplied\":false}",
            UserTccStateVO.class);
    assertNotNull(ignored);
    assertTrue(ignored.ledgerAppliedPresent);
    assertFalse(ignored.ledgerApplied);
    var strictIgnored = mapper.copy().enable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES);
    assertThrows(
        IgnoredPropertyException.class,
        () -> strictIgnored.readValue("{\"ledgerAppliedPresent\":true}", UserTccStateVO.class));
    var handled = new AtomicInteger();
    mapper.addHandler(
        new DeserializationProblemHandler() {
          /** 消费测试未知值，验证原problem handler仍可控制未知字段策略。 */
          @Override
          public boolean handleUnknownProperty(
              DeserializationContext context,
              JsonParser parser,
              JsonDeserializer<?> deserializer,
              Object instanceOrClass,
              String propertyName)
              throws IOException {
            assertTrue(instanceOrClass instanceof UserTccStateVO);
            assertEquals(instanceOrClass, parser.getParsingContext().getParent().getCurrentValue());
            handled.incrementAndGet();
            parser.skipChildren();
            return true;
          }
        });
    var custom =
        mapper.readValue(
            "{\"handled\":{\"userId\":8},\"handled\":[{\"userId\":9}],\"userId\":7}",
            UserTccStateVO.class);
    assertEquals(7L, custom.userId);
    assertEquals(2, handled.get());
  }

  /** 根类型/语法错误保持拒绝，程序化赋值、setter重入及正常输出仍可往返。 */
  @Test
  void objectDecoderKeepsRootAndProgrammaticContracts() throws Exception {
    var mapper = new ObjectMapper();
    assertNull(mapper.readValue("null", UserTccStateVO.class));
    for (String invalid :
        List.of(
            "1",
            "1.5",
            "true",
            "false",
            "\"other\"",
            "[]",
            "[{}]",
            "{\"userId\":",
            "{\"userId\":7",
            "{\"ledgerApplied\":false,\"ledgerApplied\":")) {
      assertThrows(
          JsonProcessingException.class,
          () -> mapper.readValue(invalid, UserTccStateVO.class),
          invalid);
    }
    var value = new UserTccStateVO();
    value.userId = 7L;
    value.branchId = 31L;
    value.payNo = "pay-1";
    value.orderNo = "order-1";
    value.amount = "10.00";
    value.operationKey = "tcc-debit:pay-1";
    value.ledgerKey = "pay-1";
    value.xid = "original-xid";
    value.state = "CANCELLED";
    value.setLedgerApplied(true);
    value.setLedgerApplied(false);
    String payload = mapper.writeValueAsString(value);
    assertFalse(mapper.readTree(payload).has("ledgerAppliedPresent"));
    var roundTrip = mapper.readValue(payload, UserTccStateVO.class);
    assertEquals(mapper.readTree(payload), mapper.readTree(mapper.writeValueAsString(roundTrip)));
    assertTrue(roundTrip.ledgerAppliedPresent);
    assertFalse(roundTrip.ledgerApplied);
    value.setLedgerApplied(null);
    assertFalse(value.ledgerAppliedPresent);
  }

  /** 七字段逐字保留原生文本，数字/布尔等类型不能获得文本身份。 */
  @Test
  void onlyNativeTextTokensAreUserTextEvidence() throws Exception {
    var mapper = new ObjectMapper();
    for (String field : TEXT_FIELDS) {
      for (String text : List.of("", " ", " 7 ", "10.00", "1e1", "中文🙂", "a\n\tb")) {
        var json = mapper.createObjectNode().put(field, text);
        var value = mapper.treeToValue(json, UserTccStateVO.class);
        assertEquals(text, UserTccStateVO.class.getField(field).get(value), field);
      }
      for (String token :
          List.of(
              "10",
              "10.00",
              "1e1",
              "true",
              "false",
              "null",
              "[]",
              "[10]",
              "{}",
              "{\"value\":10}")) {
        var value = mapper.readValue("{\"" + field + "\":" + token + "}", UserTccStateVO.class);
        assertNull(UserTccStateVO.class.getField(field).get(value), field + ":" + token);
      }
      var missing = mapper.readValue("{}", UserTccStateVO.class);
      assertNull(UserTccStateVO.class.getField(field).get(missing), field);
    }
  }

  /** 无效文本容器不能吞掉后续合法身份、布尔和其它文本兄弟字段。 */
  @Test
  void nestedTextValuesDoNotConsumeSiblingEvidence() throws Exception {
    var mapper = new ObjectMapper();
    for (String field : TEXT_FIELDS) {
      String sibling = field.equals("payNo") ? "orderNo" : "payNo";
      for (String token :
          List.of("[10,{\"nested\":[false]}]", "{\"value\":[10,{\"deep\":true}]}")) {
        var value =
            mapper.readValue(
                "{\""
                    + field
                    + "\":"
                    + token
                    + ",\"userId\":7,\"branchId\":31,\"ledgerApplied\":false,\""
                    + sibling
                    + "\":\"kept-text\"}",
                UserTccStateVO.class);
        assertNull(UserTccStateVO.class.getField(field).get(value), field);
        assertEquals(7L, value.userId);
        assertEquals(31L, value.branchId);
        assertTrue(value.ledgerAppliedPresent);
        assertFalse(value.ledgerApplied);
        assertEquals("kept-text", UserTccStateVO.class.getField(sibling).get(value));
      }
    }
  }

  /** public String赋值、string/null输出以及全局普通String强转保持。 */
  @Test
  void textAssignmentAndSerializationKeepTheirContract() throws Exception {
    var mapper = new ObjectMapper();
    var value = new UserTccStateVO();
    for (String field : TEXT_FIELDS) {
      String text = field + " 中文🙂 ";
      UserTccStateVO.class.getField(field).set(value, text);
      var json = mapper.readTree(mapper.writeValueAsString(value));
      assertTrue(json.get(field).isTextual(), field);
      assertEquals(text, json.get(field).textValue(), field);
      UserTccStateVO.class.getField(field).set(value, null);
      var nullJson = mapper.readTree(mapper.writeValueAsString(value));
      assertTrue(nullJson.get(field).isNull(), field);
    }
    assertEquals("10", mapper.readValue("10", String.class));
    assertEquals("true", mapper.readValue("true", String.class));
  }

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
