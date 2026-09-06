package com.cloudmall.cart.service.impl;

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
import java.util.*;
import java.util.stream.Collectors;
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
    return ApiResponse.ok(refreshItems());
  }

  @PostMapping("/items")
  /** 执行 add 相关操作。 */
  public ApiResponse<?> add(@RequestBody Item request) {
    // 1. 接收并整理 add 的业务请求。
    // 2. 执行 add 的核心业务校验与状态处理。
    // 3. 返回 add 的处理结果。
    if (request.skuId == null || request.quantity < 1)
      throw new BizException(ErrorCodes.INVALID, "购物车数量必须为正数", 400);
    ProductClient.SkuView current = currentSku(request.skuId);
    Map<String, Item> items = readMap();
    Item item = items.get(String.valueOf(request.skuId));
    if (item == null) {
      request.productId = current.productId();
      request.productName = current.productName();
      request.unitPrice = current.unitPrice();
      request.checked = true;
      request.addedAt = OffsetDateTime.now().toString();
      item = request;
    } else {
      item.quantity += request.quantity;
      item.productId = current.productId();
      item.productName = current.productName();
      item.unitPrice = current.unitPrice();
    }
    write(item);
    return ApiResponse.ok(item);
  }

  @PutMapping("/items/{skuId}")
  /** 执行 update 相关操作。 */
  public ApiResponse<?> update(@PathVariable Long skuId, @RequestBody Item request) {
    // 1. 接收并整理 update 的业务请求。
    // 2. 执行 update 的核心业务校验与状态处理。
    // 3. 返回 update 的处理结果。
    if (request.quantity < 1) throw new BizException(ErrorCodes.INVALID, "数量必须为正数", 400);
    Item item = readMap().get(String.valueOf(skuId));
    if (item == null) throw new BizException(ErrorCodes.NOT_FOUND, "购物车项不存在", 404);
    item.quantity = request.quantity;
    if (request.checked != null) item.checked = request.checked;
    write(item);
    return ApiResponse.ok(item);
  }

  @DeleteMapping("/items/{skuId}")
  /** 执行 delete 相关操作。 */
  public ApiResponse<?> delete(@PathVariable Long skuId) {
    // 1. 接收并整理 delete 的业务请求。
    // 2. 执行 delete 的核心业务校验与状态处理。
    // 3. 返回 delete 的处理结果。
    redis.opsForHash().delete(key(), String.valueOf(skuId));
    return ApiResponse.ok(null);
  }

  @PutMapping("/items/{skuId}/checked")
  /** 执行 checked 相关操作。 */
  public ApiResponse<?> checked(@PathVariable Long skuId, @RequestBody Map<String, Boolean> body) {
    // 1. 接收并整理 checked 的业务请求。
    // 2. 执行 checked 的核心业务校验与状态处理。
    // 3. 返回 checked 的处理结果。
    Item item = readMap().get(String.valueOf(skuId));
    if (item == null) throw new BizException(ErrorCodes.NOT_FOUND, "购物车项不存在", 404);
    item.checked = Boolean.TRUE.equals(body.get("checked"));
    write(item);
    return ApiResponse.ok(item);
  }

  @DeleteMapping("/checked-items")
  /** 执行 clearChecked 相关操作。 */
  public ApiResponse<?> clearChecked() {
    // 1. 接收并整理 clearChecked 的业务请求。
    // 2. 执行 clearChecked 的核心业务校验与状态处理。
    // 3. 返回 clearChecked 的处理结果。
    readItems().stream()
        .filter(i -> Boolean.TRUE.equals(i.checked))
        .forEach(i -> redis.opsForHash().delete(key(), String.valueOf(i.skuId)));
    return ApiResponse.ok(null);
  }

  @PostMapping("/settlement/preview")
  /** 执行 preview 相关操作。 */
  public ApiResponse<?> preview(@RequestBody Map<String, List<Long>> body) {
    // 1. 接收并整理 preview 的业务请求。
    // 2. 执行 preview 的核心业务校验与状态处理。
    // 3. 返回 preview 的处理结果。
    Set<Long> selected = new HashSet<>(body.getOrDefault("skuIds", List.of()));
    List<Item> items =
        readItems().stream()
            .filter(
                i ->
                    selected.isEmpty()
                        ? Boolean.TRUE.equals(i.checked)
                        : selected.contains(i.skuId))
            .collect(Collectors.toList());
    List<Map<String, Object>> invalid = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (Item item : items) {
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
        invalid.add(Map.of("skuId", item.skuId, "code", e.getCode(), "message", e.getMessage()));
      }
    }
    return ApiResponse.ok(
        Map.of(
            "items",
            items,
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
  private Map<String, Item> readMap() {
    // 1. 接收并整理 readMap 的业务请求。
    // 2. 执行 readMap 的核心业务校验与状态处理。
    // 3. 返回 readMap 的处理结果。
    Map<String, Item> result = new LinkedHashMap<>();
    redis
        .opsForHash()
        .entries(key())
        .forEach(
            (field, value) -> {
              try {
                result.put(
                    String.valueOf(field), mapper.readValue(String.valueOf(value), Item.class));
              } catch (Exception e) {
                throw new BizException(ErrorCodes.INTERNAL, "购物车数据损坏", 500);
              }
            });
    return result;
  }

  /** 执行 readItems 相关操作。 */
  private List<Item> readItems() {
    // 1. 接收并整理 readItems 的业务请求。
    // 2. 执行 readItems 的核心业务校验与状态处理。
    // 3. 返回 readItems 的处理结果。
    return new ArrayList<>(readMap().values());
  }

  /** 执行 refreshItems 相关操作。 */
  private List<Item> refreshItems() {
    // 1. 接收并整理 refreshItems 的业务请求。
    // 2. 执行 refreshItems 的核心业务校验与状态处理。
    // 3. 返回 refreshItems 的处理结果。
    List<Item> items = readItems();
    for (Item item : items) {
      try {
        ProductClient.SkuView current = currentSku(item.skuId);
        item.productId = current.productId();
        item.productName = current.productName();
        item.unitPrice = current.unitPrice();
        write(item);
      } catch (BizException ignored) {
        // Keep an invalid cart item visible so the settlement flow can report it.
      }
    }
    return items;
  }

  /** 执行 write 相关操作。 */
  private void write(Item item) {
    // 1. 接收并整理 write 的业务请求。
    // 2. 执行 write 的核心业务校验与状态处理。
    // 3. 返回 write 的处理结果。
    try {
      redis.opsForHash().put(key(), String.valueOf(item.skuId), mapper.writeValueAsString(item));
      redis.expire(key(), TTL);
    } catch (Exception e) {
      throw new BizException(ErrorCodes.INTERNAL, "购物车保存失败", 500);
    }
  }

  /** 执行 currentSku 相关操作。 */
  private ProductClient.SkuView currentSku(Long skuId) {
    // 1. 接收并整理 currentSku 的业务请求。
    // 2. 执行 currentSku 的核心业务校验与状态处理。
    // 3. 返回 currentSku 的处理结果。
    ApiResponse<ProductClient.SkuView> response = products.getSku(skuId);
    if (response == null || response.data == null)
      throw new BizException(ErrorCodes.NOT_FOUND, "商品或SKU不存在", 404);
    return response.data;
  }

  public static class Item {
    /** 保存 skuId 的业务状态或配置。 */
    public Long skuId;

    /** 保存 productId 的业务状态或配置。 */
    public Long productId;

    /** 保存 productName 的业务状态或配置。 */
    public String productName = "CloudMall 商品";

    /** 保存 unitPrice 的业务状态或配置。 */
    public String unitPrice = "0.00";

    /** 保存 quantity 的业务状态或配置。 */
    public int quantity = 1;

    /** 保存 checked 的业务状态或配置。 */
    public Boolean checked = true;

    /** 保存 addedAt 的业务状态或配置。 */
    public String addedAt;
  }
}
