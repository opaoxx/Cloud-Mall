package com.cloudmall.cart.service.impl;

import com.cloudmall.cart.domain.dto.CartCleanupSnapshotDTO;
import com.cloudmall.cart.domain.po.CartItemPO;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** 保存购物车原 JSON 并提供完整 Hash 读取、条件写删与统一 Redis 异常映射。 */
@Component
public class CartStorage {
  /** 每次成功写入后购物车保留的时间。 */
  private static final Duration TTL = Duration.ofDays(30);

  /** 仅比较原JSON字节并写原序列化结果，不用cjson解析Long/金额/时间。 */
  private static final DefaultRedisScript<Long> CART_COMPARE_WRITE =
      new DefaultRedisScript<>(
          "local current=redis.call('HGET',KEYS[1],ARGV[1]); "
              + "if ARGV[2]=='0' then if current then return 0 end "
              + "elseif current~=ARGV[3] then return 0 end; "
              + "redis.call('HSET',KEYS[1],ARGV[1],ARGV[4]); "
              + "redis.call('PEXPIRE',KEYS[1],ARGV[5]); return 1",
          Long.class);

  /** 只删除仍与初读原JSON相同的field，不解析JSON、不续TTL或追删并发变更。 */
  private static final DefaultRedisScript<Long> CART_COMPARE_DELETE =
      new DefaultRedisScript<>(
          "if redis.call('HGET',KEYS[1],ARGV[1])~=ARGV[2] then return 0 end; "
              + "return redis.call('HDEL',KEYS[1],ARGV[1])",
          Long.class);

  /** 访问购物车 Hash 并执行原子条件脚本的客户端。 */
  private final StringRedisTemplate redisTemplate;

  /** 购物车项与原始清理证明共用的 JSON 映射器。 */
  private final ObjectMapper objectMapper;

  /** 创建 Redis 购物车存储实现。 */
  public CartStorage(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
  }

  /** 显式删除指定字段，保持可移除损坏项和 Redis 故障映射的语义。 */
  void deleteItem(Long userId, Long skuId) {
    redisOperation(() -> redisTemplate.opsForHash().delete(cartKey(userId), String.valueOf(skuId)));
  }

  /** 在原持久化入口核验显式用户身份，保留请求校验与401的先后顺序。 */
  private String cartKey(Long userId) {
    if (userId == null) {
      throw new BizException(ErrorCodes.UNAUTHORIZED, "请先登录", 401);
    }
    return "cart:" + userId;
  }

  /** 同次HGETALL保留原始JSON快照供条件写/删，所有读仍沿原完整身份门。 */
  Map<String, CartItemPO> readItems(Long userId, Map<String, String> originalValues) {
    String cartKey = cartKey(userId);
    // 1. 原Hash读取保持Redis异常503，结果只在当前调用内构造。
    Map<String, CartItemPO> result = new LinkedHashMap<>();
    redisOperation(() -> redisTemplate.opsForHash().entries(cartKey))
        .forEach(
            (field, value) -> {
              try {
                // 2. 字段只能是原始规范SKU字符串，不把别名或空PO当作合法项。
                CartItemPO item = objectMapper.readValue(String.valueOf(value), CartItemPO.class);
                if (item == null
                    || item.skuId == null
                    || !(field instanceof String)
                    || !Objects.equals(field, String.valueOf(item.skuId)))
                  throw new IllegalArgumentException("购物车Hash字段与SKU身份不一致");
                result.put((String) field, item);
                if (originalValues != null)
                  originalValues.put((String) field, String.valueOf(value));
              } catch (Exception invalidItem) {
                throw new BizException(ErrorCodes.INTERNAL, "购物车数据损坏", 500);
              }
            });
    // 3. 整个读取通过才授权下游操作，任何坏行均无Redis写入/删除。
    return result;
  }

  /** 保留原Jackson序列化及保存异常语义，条件脚本不解析业务JSON。 */
  private String serializeCartItem(CartItemPO item) {
    try {
      return objectMapper.writeValueAsString(item);
    } catch (Exception serializationFailure) {
      throw new BizException(ErrorCodes.INTERNAL, "购物车保存失败", 500);
    }
  }

  /** 只有脚本明确0才可重试，执行异常或未知结果不能猜未应用。 */
  boolean compareWrite(Long userId, String skuField, String expectedJson, CartItemPO item) {
    String cartKey = cartKey(userId);
    String updatedJson = serializeCartItem(item);
    Long result =
        redisOperation(
            () ->
                redisTemplate.execute(
                    CART_COMPARE_WRITE,
                    List.of(cartKey),
                    skuField,
                    expectedJson == null ? "0" : "1",
                    expectedJson == null ? "" : expectedJson,
                    updatedJson,
                    String.valueOf(TTL.toMillis())));
    if (Long.valueOf(1).equals(result)) return true;
    if (Long.valueOf(0).equals(result)) return false;
    throw new BizException(ErrorCodes.INTERNAL, "购物车更新结果未知", 500);
  }

  /** 原JSON精确相等才删除。0为缺失或变更的确定跳过，1为确定删除；其他返回或Redis异常 不猜测是否已应用、不重试，也不补偿已删除的前序候选。 */
  boolean compareDelete(Long userId, String skuField, String expectedJson) {
    String cartKey = cartKey(userId);
    Long result =
        redisOperation(
            () ->
                redisTemplate.execute(
                    CART_COMPARE_DELETE, List.of(cartKey), skuField, expectedJson));
    if (Long.valueOf(1).equals(result)) return true;
    if (Long.valueOf(0).equals(result)) return false;
    throw new BizException(ErrorCodes.INTERNAL, "购物车清理结果未知", 500);
  }

  /** Redis 故障统一按已评审的可重试依赖错误返回。 */
  private <T> T redisOperation(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (DataAccessException redisFailure) {
      throw new BizException(ErrorCodes.REDIS_UNAVAILABLE, "依赖服务暂时不可用，请稍后重试", 503);
    }
  }

  /** 精确校验不可变证明的原数值SKU和完整JSON object，解析失败拒绝且零Redis操作。 */
  boolean matchesCleanupSnapshot(Long skuId, CartCleanupSnapshotDTO snapshot) {
    if (skuId == null
        || snapshot == null
        || !Objects.equals(skuId, snapshot.skuId())
        || snapshot.expectedJson() == null
        || snapshot.expectedJson().isBlank()) return false;
    try {
      JsonNode proof =
          objectMapper
              .reader()
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(snapshot.expectedJson());
      JsonNode proofSku = proof == null ? null : proof.path("skuId");
      return proof != null
          && proof.isObject()
          && proofSku.isIntegralNumber()
          && proofSku.canConvertToLong()
          && proofSku.longValue() == skuId.longValue();
    } catch (JsonProcessingException malformedProof) {
      return false;
    }
  }
}
