package com.cloudmall.cart;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/cart")
public class CartController {
    private static final Duration TTL = Duration.ofDays(30);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ProductClient products;
    public CartController(StringRedisTemplate redis, ObjectMapper mapper, ProductClient products) { this.redis = redis; this.mapper = mapper; this.products = products; }

    @GetMapping public ApiResponse<?> all() { return ApiResponse.ok(refreshItems()); }

    @PostMapping("/items")
    public ApiResponse<?> add(@RequestBody Item request) {
        if (request.skuId == null || request.quantity < 1) throw new BizException(ErrorCodes.INVALID, "购物车数量必须为正数", 400);
        ProductClient.SkuView current = currentSku(request.skuId);
        Map<String, Item> items = readMap(); Item item = items.get(String.valueOf(request.skuId));
        if (item == null) { request.productId = current.productId(); request.productName = current.productName(); request.unitPrice = current.unitPrice(); request.checked = true; request.addedAt = OffsetDateTime.now().toString(); item = request; } else { item.quantity += request.quantity; item.productId = current.productId(); item.productName = current.productName(); item.unitPrice = current.unitPrice(); }
        write(item); return ApiResponse.ok(item);
    }

    @PutMapping("/items/{skuId}")
    public ApiResponse<?> update(@PathVariable Long skuId, @RequestBody Item request) {
        if (request.quantity < 1) throw new BizException(ErrorCodes.INVALID, "数量必须为正数", 400);
        Item item = readMap().get(String.valueOf(skuId)); if (item == null) throw new BizException(ErrorCodes.NOT_FOUND, "购物车项不存在", 404);
        item.quantity = request.quantity; if (request.checked != null) item.checked = request.checked; write(item); return ApiResponse.ok(item);
    }

    @DeleteMapping("/items/{skuId}") public ApiResponse<?> delete(@PathVariable Long skuId) { redis.opsForHash().delete(key(), String.valueOf(skuId)); return ApiResponse.ok(null); }

    @PutMapping("/items/{skuId}/checked")
    public ApiResponse<?> checked(@PathVariable Long skuId, @RequestBody Map<String, Boolean> body) {
        Item item = readMap().get(String.valueOf(skuId)); if (item == null) throw new BizException(ErrorCodes.NOT_FOUND, "购物车项不存在", 404);
        item.checked = Boolean.TRUE.equals(body.get("checked")); write(item); return ApiResponse.ok(item);
    }

    @DeleteMapping("/checked-items")
    public ApiResponse<?> clearChecked() { readItems().stream().filter(i -> Boolean.TRUE.equals(i.checked)).forEach(i -> redis.opsForHash().delete(key(), String.valueOf(i.skuId))); return ApiResponse.ok(null); }

    @PostMapping("/settlement/preview")
    public ApiResponse<?> preview(@RequestBody Map<String, List<Long>> body) {
        Set<Long> selected = new HashSet<>(body.getOrDefault("skuIds", List.of()));
        List<Item> items = readItems().stream().filter(i -> selected.isEmpty() ? Boolean.TRUE.equals(i.checked) : selected.contains(i.skuId)).collect(Collectors.toList());
        List<Map<String,Object>> invalid = new ArrayList<>(); BigDecimal total = BigDecimal.ZERO;
        for (Item item : items) { try { ProductClient.SkuView current = currentSku(item.skuId); item.productId=current.productId(); item.productName=current.productName(); item.unitPrice=current.unitPrice(); write(item); total=total.add(new BigDecimal(current.unitPrice()).multiply(BigDecimal.valueOf(item.quantity))); } catch (BizException e) { invalid.add(Map.of("skuId", item.skuId, "code", e.getCode(), "message", e.getMessage())); } }
        return ApiResponse.ok(Map.of("items", items, "invalidItems", invalid, "totalAmount", total.toPlainString(), "payAmount", total.toPlainString()));
    }

    private String key() { return "cart:" + AuthContext.requireUserId(); }
    private Map<String, Item> readMap() {
        Map<String, Item> result = new LinkedHashMap<>();
        redis.opsForHash().entries(key()).forEach((field, value) -> { try { result.put(String.valueOf(field), mapper.readValue(String.valueOf(value), Item.class)); } catch (Exception e) { throw new BizException(ErrorCodes.INTERNAL, "购物车数据损坏", 500); } });
        return result;
    }
    private List<Item> readItems() { return new ArrayList<>(readMap().values()); }
    private List<Item> refreshItems() {
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
    private void write(Item item) { try { redis.opsForHash().put(key(), String.valueOf(item.skuId), mapper.writeValueAsString(item)); redis.expire(key(), TTL); } catch (Exception e) { throw new BizException(ErrorCodes.INTERNAL, "购物车保存失败", 500); } }
    private ProductClient.SkuView currentSku(Long skuId) { ApiResponse<ProductClient.SkuView> response = products.getSku(skuId); if (response == null || response.data == null) throw new BizException(ErrorCodes.NOT_FOUND, "商品或SKU不存在", 404); return response.data; }
    public static class Item { public Long skuId; public Long productId; public String productName = "CloudMall 商品"; public String unitPrice = "0.00"; public int quantity = 1; public Boolean checked = true; public String addedAt; }
}
