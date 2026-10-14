package com.cloudmall.pay.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** User文本证据仅接受JSON原生字符串，其它类型保持缺证。 */
public class UserTccNativeTextDeserializer extends JsonDeserializer<String> {
  /** 创建仅用于User文本字段的无状态解析器。 */
  public UserTccNativeTextDeserializer() {}

  /**
   * 逐字保留原生字符串，拒绝数字或布尔强转成业务文本。
   *
   * @param parser 当前字段值的JSON解析器
   * @param context Jackson当前反序列化上下文
   * @return 原生文本，其它值返回null供既有证据门处理
   * @throws IOException JSON值读取失败
   */
  @Override
  public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    // 1. 只接受原始字符串token，不规范化文本或新增业务词法。
    if (parser.currentToken() == JsonToken.VALUE_STRING) return parser.getText();
    // 2. 完整消费非文本容器，保留后续兄弟字段。
    parser.skipChildren();
    // 3. 非文本值不能伪装成业务键、状态或金额证明。
    return null;
  }
}
