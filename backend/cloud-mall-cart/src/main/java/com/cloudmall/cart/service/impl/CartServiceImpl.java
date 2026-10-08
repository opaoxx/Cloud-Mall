package com.cloudmall.cart.service.impl;

import com.cloudmall.cart.domain.dto.CartItemDTO;
import com.cloudmall.cart.domain.po.CartItemPO;
import com.cloudmall.cart.domain.vo.CartItemVO;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.CartService;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api/cart")
public class CartServiceImpl implements CartService {
  /** 执行 ofDays 相关操作。 */
  private static final Duration TTL = Duration.ofDays(30);

  /** 保存 redis 的业务状态或配置。 */
  private final StringRedisTemplate redis;

  /** 保存 mapper 的业务状态或配置。 */
  private final ObjectMapper mapper;

  /** 保存 products 的业务状态或配置。 */
  private final ProductClient products;

  /** 创建 CartServiceImpl 实例。 */
  public CartServiceImpl(StringRedisTemplate redis, ObjectMapper mapper, ProductClient products) {
    // 1. 接收并整理 CartController 的业务请求。
    // 2. 执行 CartController 的核心业务校验与状态处理。
    // 3. 返回 CartController 的处理结果。
    this.redis = redis;
    this.mapper = mapper;
    this.products = products;
  }

  @GetMapping
  /** 执行 all 相关操作。 */
  public ApiResponse<?> all() {
    // 1. 接收并整理 all 的业务请求。
    // 2. 执行 all 的核心业务校验与状态处理。
    // 3. 返回 all 的处理结果。
    return ApiResponse.ok(refreshItems().stream().map(CartItemVO::from).toList());
  }

  /**
   * 使用当前商品事实新增或安全合并购物车数量，拒绝时保持原Redis项及TTL。
   *
   * @param request 原SKU与正int数量请求，客户端商品摘要不作为事实
   * @return 同一持久化项构造的购物车响应
   * @throws BizException 非法请求/合并溢出为400，非正存量数据为500，Redis故障为503
   */
  @PostMapping("/items")
  public ApiResponse<?> add(@RequestBody CartItemDTO request) {
    // 1. 保留请求正数校验并读取当前商品事实。
    if (request.skuId == null || request.quantity < 1)
      throw new BizException(ErrorCodes.INVALID, "购物车数量必须为正数", 400);
    ProductClient.SkuView current = currentSku(request.skuId);
    // 2. 读取原项，新项保持原规则；存量先核数量并用long求和避免int回绕。
    Map<String, CartItemPO> items = readMap();
    CartItemPO item = items.get(String.valueOf(request.skuId));
    if (item == null) {
      // 从请求构造新项，但商品摘要只信任商品服务的当前 SKU 事实。
      item = toPersistence(request);
      item.productId = current.productId();
      item.productName = current.productName();
      item.unitPrice = current.unitPrice();
      // 首次响应和 Redis 存储必须读取同一个完整 PO。
      item.checked = true;
      item.addedAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();
    } else {
      if (item.quantity < 1) throw new BizException(ErrorCodes.INTERNAL, "购物车数量数据损坏", 500);
      long mergedQuantity = (long) item.quantity + request.quantity;
      if (mergedQuantity > Integer.MAX_VALUE)
        throw new BizException(ErrorCodes.INVALID, "购物车数量超过支持范围", 400);
      item.quantity = (int) mergedQuantity;
      item.productId = current.productId();
      item.productName = current.productName();
      item.unitPrice = current.unitPrice();
    }
    // 3. 持久化并从同一 PO 构造响应，避免首次添加与随后读取不一致。
    write(item);
    return ApiResponse.ok(CartItemVO.from(item));
  }

  @PutMapping("/items/{skuId}")
  /** 执行 update 相关操作。 */
  public ApiResponse<?> update(@PathVariable Long skuId, @RequestBody CartItemDTO request) {
    // 1. 接收并整理 update 的业务请求。
    // 2. 执行 update 的核心业务校验与状态处理。
    // 3. 返回 update 的处理结果。
    if (request.quantity < 1) throw new BizException(ErrorCodes.INVALID, "数量必须为正数", 400);
    CartItemPO item = readMap().get(String.valueOf(skuId));
    if (item == null) throw new BizException(ErrorCodes.NOT_FOUND, "购物车项不存在", 404);
    item.quantity = request.quantity;
    if (request.checked != null) item.checked = request.checked;
    write(item);
    return ApiResponse.ok(CartItemVO.from(item));
  }

  @DeleteMapping("/items/{skuId}")
  /** 执行 delete 相关操作。 */
  public ApiResponse<?> delete(@PathVariable Long skuId) {
    // 1. 接收并整理 delete 的业务请求。
    // 2. 执行 delete 的核心业务校验与状态处理。
    // 3. 返回 delete 的处理结果。
    redisOperation(() -> redis.opsForHash().delete(key(), String.valueOf(skuId)));
    return ApiResponse.ok(null);
  }

  @PutMapping("/items/{skuId}/checked")
  /** 执行 checked 相关操作。 */
  public ApiResponse<?> checked(@PathVariable Long skuId, @RequestBody Map<String, Boolean> body) {
    // 1. 接收并整理 checked 的业务请求。
    // 2. 执行 checked 的核心业务校验与状态处理。
    // 3. 返回 checked 的处理结果。
    CartItemPO item = readMap().get(String.valueOf(skuId));
    if (item == null) throw new BizException(ErrorCodes.NOT_FOUND, "购物车项不存在", 404);
    item.checked = Boolean.TRUE.equals(body.get("checked"));
    write(item);
    return ApiResponse.ok(CartItemVO.from(item));
  }

  @DeleteMapping("/checked-items")
  /** 执行 clearChecked 相关操作。 */
  public ApiResponse<?> clearChecked() {
    // 1. 接收并整理 clearChecked 的业务请求。
    // 2. 执行 clearChecked 的核心业务校验与状态处理。
    // 3. 返回 clearChecked 的处理结果。
    readItems().stream()
        .filter(i -> Boolean.TRUE.equals(i.checked))
        .forEach(i -> redisOperation(() -> redis.opsForHash().delete(key(), String.valueOf(i.skuId))));
    return ApiResponse.ok(null);
  }

  @PostMapping("/settlement/preview")
  /** 执行 preview 相关操作。 */
  public ApiResponse<?> preview(@RequestBody Map<String, List<Long>> body) {
    // 1. 接收并整理 preview 的业务请求。
    // 2. 执行 preview 的核心业务校验与状态处理。
    // 3. 返回 preview 的处理结果。
    Set<Long> selected = new HashSet<>(body.getOrDefault("skuIds", List.of()));
    List<CartItemPO> items =
        readItems().stream()
            .filter(
                i ->
                    selected.isEmpty()
                        ? Boolean.TRUE.equals(i.checked)
                        : selected.contains(i.skuId))
            .collect(Collectors.toList());
    List<Map<String, Object>> invalid = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (CartItemPO item : items) {
      try {
        ProductClient.SkuView current = currentSku(item.skuId);
        item.productId = current.productId();
        item.productName = current.productName();
        item.unitPrice = current.unitPrice();
        write(item);
        total =
            total.add(
                new BigDecimal(current.unitPrice()).multiply(BigDecimal.valueOf(item.quantity)));
      } catch (BizException e) {
        if (e.getHttpStatus() >= 500) throw e;
        invalid.add(Map.of("skuId", item.skuId, "code", e.getCode(), "message", e.getMessage()));
      }
    }
    return ApiResponse.ok(
        Map.of(
            "items",
            items.stream().map(CartItemVO::from).toList(),
            "invalidItems",
            invalid,
            "totalAmount",
            total.toPlainString(),
            "payAmount",
            total.toPlainString()));
  }

  /** 执行 key 相关操作。 */
  private String key() {
    // 1. 接收并整理 key 的业务请求。
    // 2. 执行 key 的核心业务校验与状态处理。
    // 3. 返回 key 的处理结果。
    return "cart:" + AuthContext.requireUserId();
  }

  /** 执行 readMap 相关操作。 */
  private Map<String, CartItemPO> readMap() {
    // 1. 接收并整理 readMap 的业务请求。
    // 2. 执行 readMap 的核心业务校验与状态处理。
    // 3. 返回 readMap 的处理结果。
    Map<String, CartItemPO> result = new LinkedHashMap<>();
    redisOperation(() -> redis.opsForHash().entries(key()))
        .forEach(
            (field, value) -> {
              try {
                result.put(
                    String.valueOf(field),
                    mapper.readValue(String.valueOf(value), CartItemPO.class));
              } catch (Exception e) {
                throw new BizException(ErrorCodes.INTERNAL, "购物车数据损坏", 500);
              }
            });
    return result;
  }

  /** 执行 readItems 相关操作。 */
  private List<CartItemPO> readItems() {
    // 1. 接收并整理 readItems 的业务请求。
    // 2. 执行 readItems 的核心业务校验与状态处理。
    // 3. 返回 readItems 的处理结果。
    return new ArrayList<>(readMap().values());
  }

  /** 执行 refreshItems 相关操作。 */
  private List<CartItemPO> refreshItems() {
    // 1. 接收并整理 refreshItems 的业务请求。
    // 2. 执行 refreshItems 的核心业务校验与状态处理。
    // 3. 返回 refreshItems 的处理结果。
    List<CartItemPO> items = readItems();
    for (CartItemPO item : items) {
      try {
        ProductClient.SkuView current = currentSku(item.skuId);
        item.productId = current.productId();
        item.productName = current.productName();
        item.unitPrice = current.unitPrice();
        write(item);
      } catch (BizException ignored) {
        if (ignored.getHttpStatus() >= 500) throw ignored;
        // Keep an invalid cart item visible so the settlement flow can report it.
      }
    }
    return items;
  }

  /** 执行 write 相关操作。 */
  private void write(CartItemPO item) {
    // 1. 接收并整理 write 的业务请求。
    // 2. 执行 write 的核心业务校验与状态处理。
    // 3. 返回 write 的处理结果。
    String json;
    try {
      json = mapper.writeValueAsString(item);
    } catch (Exception e) {
      throw new BizException(ErrorCodes.INTERNAL, "购物车保存失败", 500);
    }
    redisOperation(() -> {
      redis.opsForHash().put(key(), String.valueOf(item.skuId), json);
      redis.expire(key(), TTL);
      return null;
    });
  }

  /** Redis 故障统一按已评审的可重试依赖错误返回。 */
  private <T> T redisOperation(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (DataAccessException redisFailure) {
      throw new BizException(ErrorCodes.REDIS_UNAVAILABLE, "依赖服务暂时不可用，请稍后重试", 503);
    }
  }

  /** 读取成功且原SKU身份一致的商品事实，坏证明抛500阻止当前项回写/结算。 */
  private ProductClient.SkuView currentSku(Long skuId) {
    // 1. 保持原查询和缺失响应/数据的404语义。
    ApiResponse<ProductClient.SkuView> response = products.getSku(skuId);
    if (response == null || response.data == null)
      throw new BizException(ErrorCodes.NOT_FOUND, "商品或SKU不存在", 404);
    // 2. 可反序列化不代表可信，错误码或别SKU不得提供当前购物车价格。
    if (!"0".equals(response.code)
        || skuId == null
        || !Objects.equals(skuId, response.data.skuId()))
      throw new BizException(ErrorCodes.INTERNAL, "商品SKU响应身份或状态不可信", 500);
    // 3. 只有精确原身份的成功事实进入既有摘要/价格处理。
    return response.data;
  }

  /** 将购物车请求转换为 Redis 持久化对象。 */
  private CartItemPO toPersistence(CartItemDTO request) {
    CartItemPO item = new CartItemPO();
    item.skuId = request.skuId;
    item.productId = request.productId;
    item.productName = request.productName;
    item.unitPrice = request.unitPrice;
    item.quantity = request.quantity;
    item.checked = request.checked;
    item.addedAt = request.addedAt;
    return item;
  }
}
