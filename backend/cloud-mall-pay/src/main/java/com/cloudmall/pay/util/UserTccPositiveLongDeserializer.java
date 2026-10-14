package com.cloudmall.pay.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigInteger;

/** User身份字段仅接受JSON原生正Long整数，其它类型保持缺证。 */
public class UserTccPositiveLongDeserializer extends JsonDeserializer<Long> {
  /** 身份字段可表示的最大正整数，收窄到Long前必须检查。 */
  private static final BigInteger MAX_ID = BigInteger.valueOf(Long.MAX_VALUE);

  /** 创建仅用于User身份字段的无状态解析器。 */
  public UserTccPositiveLongDeserializer() {}

  /**
   * 校验原始整数token及Long正数域，拒绝小数截断、字符串强转和溢出。
   *
   * @param parser 当前字段值的JSON解析器
   * @param context Jackson当前反序列化上下文
   * @return 原生正Long值，其它值返回null供原证据门处理
   * @throws IOException JSON值读取失败
   */
  @Override
  public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    // 1. 检查原始token，完整消费非整数容器，保留后续兄弟字段。
    if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
      parser.skipChildren();
      return null;
    }
    // 2. 先读取任意精度整数，避免越界值在Long转换时丢失信息。
    BigInteger identity = parser.getBigIntegerValue();
    if (identity.signum() <= 0 || identity.compareTo(MAX_ID) > 0) return null;
    // 3. 仅在正Long范围已确认后执行无损转换。
    return identity.longValue();
  }
}
