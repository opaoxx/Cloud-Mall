package com.cloudmall.pay.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** User账本观察仅接受JSON原生布尔，其它类型保持缺证。 */
public class UserTccLedgerBooleanDeserializer extends JsonDeserializer<Boolean> {
  /** 创建仅用于User账本字段的无状态解析器。 */
  public UserTccLedgerBooleanDeserializer() {}

  /**
   * 保留原生布尔；消费完整非布尔值并以null交由既有setter标记缺证。
   *
   * @param parser 当前字段值的JSON解析器
   * @param context Jackson当前反序列化上下文
   * @return 原生布尔值，非布尔值返回null
   * @throws IOException JSON值读取失败
   */
  @Override
  public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    // 1. 检查原始token，不使用会执行标量强转的读取方法。
    JsonToken token = parser.currentToken();
    // 2. 只有JSON原生true/false是明确的账本观察。
    if (token == JsonToken.VALUE_TRUE) return Boolean.TRUE;
    if (token == JsonToken.VALUE_FALSE) return Boolean.FALSE;
    // 3. 跳过嵌套容器，保留后续兄弟字段，并让原setter按缺证处理。
    parser.skipChildren();
    return null;
  }
}
