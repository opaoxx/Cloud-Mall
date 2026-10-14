package com.cloudmall.pay.util;

import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.IgnoredPropertyException;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** User状态根对象的已知字段必须唯一，防止末值覆盖矛盾证据。 */
public class UserTccStateDeserializer extends JsonDeserializer<UserTccStateVO> {
  /** 参与证据绑定的十个根字段；内部presence和未知字段不计入重复集合。 */
  private static final Set<String> KNOWN_FIELDS =
      Set.of(
          "userId",
          "payNo",
          "orderNo",
          "amount",
          "operationKey",
          "ledgerKey",
          "xid",
          "branchId",
          "state",
          "ledgerApplied");

  /** 复用原生正Long及数值边界规则的无状态解析器。 */
  private static final UserTccPositiveLongDeserializer IDENTITIES =
      new UserTccPositiveLongDeserializer();

  /** 复用原生文本且不规范化内容的无状态解析器。 */
  private static final UserTccNativeTextDeserializer TEXT = new UserTccNativeTextDeserializer();

  /** 复用原生布尔与缺证规则的无状态解析器。 */
  private static final UserTccLedgerBooleanDeserializer LEDGER =
      new UserTccLedgerBooleanDeserializer();

  /** 创建仅绑定User状态响应的无状态对象解析器。 */
  public UserTccStateDeserializer() {}

  /**
   * 按既有类型规则消费对象，重复已知根字段使整份响应失去证据资格。
   *
   * @param parser 当前响应值的JSON解析器
   * @param context Jackson当前反序列化上下文
   * @return 唯一字段的状态响应，存在重复已知字段则返回null
   * @throws IOException JSON读取失败或原Jackson未知字段/根类型规则拒绝
   */
  @Override
  public UserTccStateVO deserialize(JsonParser parser, DeserializationContext context)
      throws IOException {
    // 1. 明确保留null语义，非对象根沿Jackson原异常处理。
    if (parser.currentToken() == JsonToken.VALUE_NULL) return null;
    if (parser.currentToken() != JsonToken.START_OBJECT)
      return (UserTccStateVO) context.handleUnexpectedToken(UserTccStateVO.class, parser);
    UserTccStateVO value = new UserTccStateVO();
    parser.setCurrentValue(value);
    Set<String> seen = new HashSet<>();
    boolean ambiguous = false;
    // 2. 只跟踪已知根字段，重复后仍完整消费对象，不能用后值恢复可信。
    for (JsonToken token = parser.nextToken();
        token != JsonToken.END_OBJECT;
        token = parser.nextToken()) {
      if (token != JsonToken.FIELD_NAME)
        return context.reportInputMismatch(UserTccStateVO.class, "User状态响应缺少字段名");
      String field = parser.currentName();
      if (parser.nextToken() == null)
        return context.reportInputMismatch(UserTccStateVO.class, "User状态响应缺少字段值");
      if (KNOWN_FIELDS.contains(field) && !seen.add(field)) {
        ambiguous = true;
        parser.skipChildren();
        continue;
      }
      switch (field) {
        case "userId" -> value.userId = IDENTITIES.deserialize(parser, context);
        case "branchId" -> value.branchId = IDENTITIES.deserialize(parser, context);
        case "payNo" -> value.payNo = TEXT.deserialize(parser, context);
        case "orderNo" -> value.orderNo = TEXT.deserialize(parser, context);
        case "amount" -> value.amount = TEXT.deserialize(parser, context);
        case "operationKey" -> value.operationKey = TEXT.deserialize(parser, context);
        case "ledgerKey" -> value.ledgerKey = TEXT.deserialize(parser, context);
        case "xid" -> value.xid = TEXT.deserialize(parser, context);
        case "state" -> value.state = TEXT.deserialize(parser, context);
        case "ledgerApplied" -> value.setLedgerApplied(LEDGER.deserialize(parser, context));
        case "ledgerAppliedPresent" -> {
          if (context.isEnabled(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES))
            throw IgnoredPropertyException.from(parser, value, field, null);
          parser.skipChildren();
        }
        default -> context.handleUnknownProperty(parser, this, value, field);
      }
    }
    // 3. 仅发布无歧义的完整对象，缺证沿调用方原202/UNKNOWN路径处理。
    return ambiguous ? null : value;
  }
}
