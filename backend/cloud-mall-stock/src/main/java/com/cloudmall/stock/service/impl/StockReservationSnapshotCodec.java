package com.cloudmall.stock.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.stock.domain.dto.StockLineDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/** 普通预扣writer和reader共享的快照序列化与摘要。 */
@Component
public class StockReservationSnapshotCodec {
  /** 与业务请求使用同一序列化配置的JSON映射器。 */
  private final ObjectMapper mapper;

  /** 创建共享的规范快照编解码能力。 */
  public StockReservationSnapshotCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** 将排序后的 SKU/数量快照写成稳定 JSON。 */
  public String toJson(List<StockLineDTO> lines) {
    try {
      return mapper.writeValueAsString(lines);
    } catch (com.fasterxml.jackson.core.JsonProcessingException serializationFailure) {
      throw new BizException(ErrorCodes.INVALID, "库存明细无法序列化", 400);
    }
  }

  /** 计算预扣明细的不可变 SHA-256 请求摘要。 */
  public static String sha256(String itemsSnapshot) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(itemsSnapshot.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException unavailableAlgorithm) {
      throw new IllegalStateException("JDK 缺少 SHA-256", unavailableAlgorithm);
    }
  }
}
