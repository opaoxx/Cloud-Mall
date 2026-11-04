package com.cloudmall.product.util;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 集中处理商品字段的 JSON 与金额格式转换。 */
@Component
public class ProductValueCodec {

  /** 商品规格读写使用的应用 JSON 映射器。 */
  private final ObjectMapper objectMapper;

  /** 注入原 JSON 映射器。 */
  public ProductValueCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** 将规格对象编码为原 JSON 文本，序列化错误沿原400返回。 */
  public String encodeSkuSpec(Map<String, String> specJson) {
    try {
      return objectMapper.writeValueAsString(specJson == null ? Collections.emptyMap() : specJson);
    } catch (JsonProcessingException invalidValue) {
      throw new BizException(ErrorCodes.INVALID, "SKU规格格式错误", 400);
    }
  }

  /** 读取原 JSON 对象并逐项验证字符串值，保留空值和异常语义。 */
  public Map<String, String> decodeSkuSpec(Object value) {
    try {
      JsonNode node = objectMapper.readTree(value == null ? "{}" : String.valueOf(value));
      if (!node.isObject()) throw new BizException(ErrorCodes.INVALID, "SKU规格格式错误", 400);
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext())
        if (!fields.next().getValue().isTextual())
          throw new BizException(ErrorCodes.INVALID, "SKU规格格式错误", 400);
      return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (JsonProcessingException | IllegalArgumentException invalidValue) {
      throw new BizException(ErrorCodes.INVALID, "SKU规格格式错误", 400);
    }
  }

  /** 按原金额字符串规则保留两位小数并映射格式错误。 */
  public String formatPrice(String value) {
    try {
      return new BigDecimal(value == null ? "0" : value).setScale(2).toPlainString();
    } catch (Exception invalidValue) {
      throw new BizException(ErrorCodes.INVALID, "金额格式错误", 400);
    }
  }

  /** 将数据库金额按原两位小数规则转换为文本。 */
  public String formatPrice(BigDecimal value) {
    return value.setScale(2).toPlainString();
  }
}
