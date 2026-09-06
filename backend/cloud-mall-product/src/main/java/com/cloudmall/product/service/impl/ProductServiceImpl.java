package com.cloudmall.product.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.api.PageResult;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.product.domain.dto.ActivityDTO;
import com.cloudmall.product.domain.dto.CategoryDTO;
import com.cloudmall.product.domain.dto.ProductDTO;
import com.cloudmall.product.domain.dto.ProductParameterDTO;
import com.cloudmall.product.domain.dto.SkuDTO;
import com.cloudmall.product.domain.vo.ActivityVO;
import com.cloudmall.product.domain.vo.HotStatVO;
import com.cloudmall.product.mapper.ProductSqlMapper;
import com.cloudmall.product.service.ProductService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import javax.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api")
public class ProductServiceImpl implements ProductService {
  /** 执行 getLogger 相关操作。 */
  private static final Logger log = LoggerFactory.getLogger(ProductServiceImpl.class);

  /** 保存 db 的业务状态或配置。 */
  @Autowired private ProductSqlMapper productSqlMapper;

  /** 保存 rabbit 的业务状态或配置。 */
  @Autowired private RabbitTemplate rabbit;

  /** 保存 redis 的业务状态或配置。 */
  @Autowired private StringRedisTemplate redis;

  /** 保存 objectMapper 的业务状态或配置。 */
  @Autowired private ObjectMapper objectMapper;

  /** 创建由 Spring 字段注入依赖的商品服务实例。 */
  public ProductServiceImpl() {}

  /** 创建 ProductServiceImpl 实例。 */
  public ProductServiceImpl(
      ProductSqlMapper productSqlMapper,
      RabbitTemplate rabbit,
      StringRedisTemplate redis,
      ObjectMapper objectMapper) {
    // 1. 接收并整理 ProductController 的业务请求。
    // 2. 执行 ProductController 的核心业务校验与状态处理。
    // 3. 返回 ProductController 的处理结果。
    this.productSqlMapper = productSqlMapper;
    this.rabbit = rabbit;
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/categories")
  /** 执行 categories 相关操作。 */
  public ApiResponse<?> categories(
      @RequestParam(required = false) Long parentId,
      @RequestParam(required = false) Integer status) {
    // 1. 接收并整理 categories 的业务请求。
    // 2. 执行 categories 的核心业务校验与状态处理。
    // 3. 返回 categories 的处理结果。
    return ApiResponse.ok(
        productSqlMapper.findCategories(parentId, status).stream()
            .map(this::categoryView)
            .toList());
  }

  @Transactional
  @PostMapping("/categories")
  /** 执行 addCategory 相关操作。 */
  public ApiResponse<?> addCategory(@RequestBody CategoryDTO c) {
    // 1. 接收并整理 addCategory 的业务请求。
    // 2. 执行 addCategory 的核心业务校验与状态处理。
    // 3. 返回 addCategory 的处理结果。
    AuthContext.requireAdmin();
    validateCategory(c);
    long id = id();
    productSqlMapper.insertCategory(id, c, now());
    c.id = id;
    c.status = 1;
    event("CATEGORY_CHANGED", id);
    return ApiResponse.ok(c);
  }

  @Transactional
  @PutMapping("/categories/{id}")
  /** 执行 updateCategory 相关操作。 */
  public ApiResponse<?> updateCategory(@PathVariable Long id, @RequestBody CategoryDTO c) {
    // 1. 接收并整理 updateCategory 的业务请求。
    // 2. 执行 updateCategory 的核心业务校验与状态处理。
    // 3. 返回 updateCategory 的处理结果。
    AuthContext.requireAdmin();
    validateCategory(c);
    requireCategory(id);
    productSqlMapper.updateCategory(id, c, now());
    c.id = id;
    c.status = 1;
    event("CATEGORY_CHANGED", id);
    return ApiResponse.ok(c);
  }

  @Transactional
  @DeleteMapping("/categories/{id}")
  /** 执行 deleteCategory 相关操作。 */
  public ApiResponse<?> deleteCategory(@PathVariable Long id) {
    // 1. 接收并整理 deleteCategory 的业务请求。
    // 2. 执行 deleteCategory 的核心业务校验与状态处理。
    // 3. 返回 deleteCategory 的处理结果。
    AuthContext.requireAdmin();
    requireCategory(id);
    productSqlMapper.disableCategory(id, now());
    event("CATEGORY_CHANGED", id);
    return ApiResponse.ok(null);
  }

  @GetMapping("/products")
  /** 执行 products 相关操作。 */
  public ApiResponse<?> products(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) Integer status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String sort) {
    // 1. 接收并整理 products 的业务请求。
    // 2. 执行 products 的核心业务校验与状态处理。
    // 3. 返回 products 的处理结果。
    page = Math.max(1, page);
    pageSize = Math.min(Math.max(1, pageSize), 100);
    long total = productSqlMapper.countProducts(keyword, categoryId, status);
    List<ProductDTO> items =
        productSqlMapper
            .findProducts(keyword, categoryId, status, (page - 1) * pageSize, pageSize)
            .stream()
            .map(this::productView)
            .toList();
    return ApiResponse.ok(new PageResult<>(items, page, pageSize, total));
  }

  @GetMapping("/products/{id}")
  /** 执行 product 相关操作。 */
  public ApiResponse<?> product(@PathVariable Long id) {
    // 1. 接收并整理 product 的业务请求。
    // 2. 执行 product 的核心业务校验与状态处理。
    // 3. 返回 product 的处理结果。
    return ApiResponse.ok(requireProduct(id));
  }

  @GetMapping("/products/skus/{skuId}")
  /** 执行 sku 相关操作。 */
  public ApiResponse<?> sku(@PathVariable Long skuId) {
    // 1. 接收并整理 sku 的业务请求。
    // 2. 执行 sku 的核心业务校验与状态处理。
    // 3. 返回 sku 的处理结果。
    List<Map<String, Object>> x = productSqlMapper.findSku(skuId);
    if (x.isEmpty()) throw new BizException("PRODUCT_SKU_NOT_FOUND", "SKU不存在", 404);
    Map<String, Object> row = x.get(0);
    if (((Number) row.get("product_status")).intValue() != 1
        || ((Number) row.get("status")).intValue() != 1)
      throw new BizException("PRODUCT_OFF_SHELF", "商品已下架", 409);
    return ApiResponse.ok(
        Map.of(
            "skuId",
            row.get("sku_id"),
            "productId",
            row.get("product_id"),
            "productName",
            row.get("name"),
            "skuCode",
            row.get("sku_code"),
            "unitPrice",
            money((BigDecimal) row.get("price")),
            "skuSnapshot",
            readSpecJson(row.get("spec_json"))));
  }

  @GetMapping("/products/{id}/hot-stat")
  /** 执行 hot 相关操作。 */
  public ApiResponse<?> hot(@PathVariable Long id) {
    // 1. 接收并整理 hot 的业务请求。
    // 2. 执行 hot 的核心业务校验与状态处理。
    // 3. 返回 hot 的处理结果。
    requireProduct(id);
    List<HotStatVO> x = productSqlMapper.findHotStat(id).stream().map(this::hotView).toList();
    return ApiResponse.ok(x.isEmpty() ? new HotStatVO(id, 0, 0, "0.000000") : x.get(0));
  }

  @Transactional
  @PostMapping("/products")
  /** 执行 create 相关操作。 */
  public ApiResponse<?> create(@Valid @RequestBody ProductDTO p) {
    // 1. 接收并整理 create 的业务请求。
    // 2. 执行 create 的核心业务校验与状态处理。
    // 3. 返回 create 的处理结果。
    AuthContext.requireAdmin();
    validateProduct(p);
    if (p.parameters == null) p.parameters = new ArrayList<>();
    long id = id();
    save(id, p);
    p.id = id;
    p.published = false;
    event("PRODUCT_CHANGED", id);
    return ApiResponse.ok(p);
  }

  @Transactional
  @PutMapping("/products/{id}")
  /** 执行 update 相关操作。 */
  public ApiResponse<?> update(@PathVariable Long id, @RequestBody ProductDTO p) {
    // 1. 接收并整理 update 的业务请求。
    // 2. 执行 update 的核心业务校验与状态处理。
    // 3. 返回 update 的处理结果。
    AuthContext.requireAdmin();
    validateProduct(p);
    requireProduct(id);
    productSqlMapper.updateProduct(id, p, money(p.price), now());
    if (p.skus != null) replaceSkus(id, p.skus);
    if (p.parameters != null) replaceParameters(id, p.parameters);
    ProductDTO saved = requireProduct(id);
    event("PRODUCT_CHANGED", id);
    return ApiResponse.ok(saved);
  }

  @Transactional
  @PostMapping("/products/{id}/publish")
  /** 执行 publish 相关操作。 */
  public ApiResponse<?> publish(@PathVariable Long id) {
    // 1. 接收并整理 publish 的业务请求。
    // 2. 执行 publish 的核心业务校验与状态处理。
    // 3. 返回 publish 的处理结果。
    AuthContext.requireAdmin();
    return change(id, 1);
  }

  @Transactional
  @PostMapping("/products/{id}/unpublish")
  /** 执行 unpublish 相关操作。 */
  public ApiResponse<?> unpublish(@PathVariable Long id) {
    // 1. 接收并整理 unpublish 的业务请求。
    // 2. 执行 unpublish 的核心业务校验与状态处理。
    // 3. 返回 unpublish 的处理结果。
    AuthContext.requireAdmin();
    return change(id, 0);
  }

  @GetMapping("/seckill/activities/{id}")
  /** 执行 activity 相关操作。 */
  public ApiResponse<?> activity(@PathVariable Long id) {
    // 1. 接收并整理 activity 的业务请求。
    // 2. 执行 activity 的核心业务校验与状态处理。
    // 3. 返回 activity 的处理结果。
    return ApiResponse.ok(toActivityResponse(activityRecord(id)));
  }

  @PostMapping("/seckill/activities")
  /** 执行 createActivity 相关操作。 */
  public ApiResponse<?> createActivity(@RequestBody ActivityDTO a) {
    // 1. 接收并整理 createActivity 的业务请求。
    // 2. 执行 createActivity 的核心业务校验与状态处理。
    // 3. 返回 createActivity 的处理结果。
    AuthContext.requireAdmin();
    validateActivity(a);
    long id = id();
    productSqlMapper.insertActivity(id, a, ts(now()));
    return activity(id);
  }

  @PutMapping("/seckill/activities/{id}")
  /** 执行 updateActivity 相关操作。 */
  public ApiResponse<?> updateActivity(@PathVariable Long id, @RequestBody ActivityDTO a) {
    // 1. 接收并整理 updateActivity 的业务请求。
    // 2. 执行 updateActivity 的核心业务校验与状态处理。
    // 3. 返回 updateActivity 的处理结果。
    AuthContext.requireAdmin();
    validateActivity(a);
    activity(id);
    productSqlMapper.updateActivity(id, a, ts(now()));
    return activity(id);
  }

  @PostMapping("/seckill/activities/{id}/publish")
  /** 执行 publishActivity 相关操作。 */
  public ApiResponse<?> publishActivity(@PathVariable Long id) {
    // 1. 接收并整理 publishActivity 的业务请求。
    // 2. 执行 publishActivity 的核心业务校验与状态处理。
    // 3. 返回 publishActivity 的处理结果。
    AuthContext.requireAdmin();
    ActivityRecord a = activityRecord(id);
    productSqlMapper.updateActivityStatus(id, "PUBLISHED", ts(now()));
    String suffix = id + ":" + a.skuId;
    Duration ttl = Duration.ofSeconds(Math.max(1, secondsUntil(a.endAt)));
    redis.opsForValue().set("seckill:stock:" + suffix, String.valueOf(a.stockLimit), ttl);
    redis
        .opsForValue()
        .set("seckill:product:" + suffix, String.valueOf(toActivityResponse(a)), ttl);
    redis
        .opsForHash()
        .put("seckill:meta:" + suffix, "perUserLimit", String.valueOf(a.perUserLimit));
    redis.opsForHash().put("seckill:meta:" + suffix, "stockLimit", String.valueOf(a.stockLimit));
    redis
        .opsForHash()
        .put("seckill:meta:" + suffix, "startAt", toOffset(a.startAt).toInstant().toString());
    redis
        .opsForHash()
        .put("seckill:meta:" + suffix, "endAt", toOffset(a.endAt).toInstant().toString());
    redis.expire("seckill:meta:" + suffix, ttl);
    return activity(id);
  }

  @PostMapping("/seckill/activities/{id}/start")
  /** 执行 startActivity 相关操作。 */
  public ApiResponse<?> startActivity(@PathVariable Long id) {
    // 1. 接收并整理 startActivity 的业务请求。
    // 2. 执行 startActivity 的核心业务校验与状态处理。
    // 3. 返回 startActivity 的处理结果。
    AuthContext.requireAdmin();
    activityRecord(id);
    productSqlMapper.updateActivityStatus(id, "STARTED", ts(now()));
    return activity(id);
  }

  /** 执行 change 相关操作。 */
  private ApiResponse<?> change(Long id, int st) {
    // 1. 接收并整理 change 的业务请求。
    // 2. 执行 change 的核心业务校验与状态处理。
    // 3. 返回 change 的处理结果。
    ProductDTO p = requireProduct(id);
    productSqlMapper.updateProductStatus(id, st, now());
    p.status = st;
    p.published = st == 1;
    event("PRODUCT_CHANGED", id);
    return ApiResponse.ok(p);
  }

  /** 执行 requireProduct 相关操作。 */
  private ProductDTO requireProduct(Long id) {
    // 1. 接收并整理 requireProduct 的业务请求。
    // 2. 执行 requireProduct 的核心业务校验与状态处理。
    // 3. 返回 requireProduct 的处理结果。
    List<ProductDTO> x = productSqlMapper.findProduct(id).stream().map(this::productView).toList();
    if (x.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "商品不存在", 404);
    return x.get(0);
  }

  /** 执行 requireCategory 相关操作。 */
  private void requireCategory(Long id) {
    // 1. 接收并整理 requireCategory 的业务请求。
    // 2. 执行 requireCategory 的核心业务校验与状态处理。
    // 3. 返回 requireCategory 的处理结果。
    if (productSqlMapper.countCategory(id) == 0)
      throw new BizException(ErrorCodes.NOT_FOUND, "分类不存在", 404);
  }

  /** 将分类数据库行转换为分类传输对象。 */
  private CategoryDTO categoryView(Map<String, Object> row) {
    return new CategoryDTO(
        ((Number) row.get("id")).longValue(),
        ((Number) row.get("parent_id")).longValue(),
        String.valueOf(row.get("name")),
        ((Number) row.get("sort_no")).intValue(),
        ((Number) row.get("status")).intValue());
  }

  /** 将商品数据库行转换为商品传输对象并加载附属数据。 */
  private ProductDTO productView(Map<String, Object> row) {
    return read(
        ((Number) row.get("id")).longValue(),
        ((Number) row.get("category_id")).longValue(),
        String.valueOf(row.get("name")),
        (String) row.get("main_image"),
        (String) row.get("description"),
        (BigDecimal) row.get("price"),
        ((Number) row.get("status")).intValue());
  }

  /** 将 SKU 数据库行转换为 SKU 传输对象。 */
  private SkuDTO skuView(Map<String, Object> row) {
    return new SkuDTO(
        ((Number) row.get("id")).longValue(),
        ((Number) row.get("product_id")).longValue(),
        String.valueOf(row.get("sku_code")),
        readSpecJson(row.get("spec_json")),
        money((BigDecimal) row.get("price")),
        ((Number) row.get("status")).intValue() == 1);
  }

  /** 将商品参数数据库行转换为参数传输对象。 */
  private ProductParameterDTO parameterView(Map<String, Object> row) {
    return new ProductParameterDTO(
        ((Number) row.get("id")).longValue(),
        ((Number) row.get("product_id")).longValue(),
        String.valueOf(row.get("param_name")),
        String.valueOf(row.get("param_value")),
        ((Number) row.get("sort_no")).intValue());
  }

  /** 将热度数据库行转换为热度响应视图。 */
  private HotStatVO hotView(Map<String, Object> row) {
    BigDecimal hotScore = (BigDecimal) row.get("hot_score");
    return new HotStatVO(
        ((Number) row.get("product_id")).longValue(),
        ((Number) row.get("view_count")).longValue(),
        ((Number) row.get("search_count")).longValue(),
        hotScore == null ? "0.000000" : hotScore.toPlainString());
  }

  /** 执行 read 相关操作。 */
  private ProductDTO read(
      long id, long cat, String rowNumber, String image, String desc, BigDecimal price, int st) {
    // 1. 接收并整理 read 的业务请求。
    // 2. 执行 read 的核心业务校验与状态处理。
    // 3. 返回 read 的处理结果。
    ProductDTO p = new ProductDTO();
    p.id = id;
    p.categoryId = cat;
    p.name = rowNumber;
    p.mainImage = image;
    p.description = desc;
    p.price = money(price);
    p.status = st;
    p.published = st == 1;
    p.skus = productSqlMapper.findSkus(id).stream().map(this::skuView).toList();
    p.parameters = productSqlMapper.findParameters(id).stream().map(this::parameterView).toList();
    return p;
  }

  /** 执行 save 相关操作。 */
  private void save(long id, ProductDTO p) {
    // 1. 接收并整理 save 的业务请求。
    // 2. 执行 save 的核心业务校验与状态处理。
    // 3. 返回 save 的处理结果。
    productSqlMapper.insertProduct(id, p, money(p.price), now());
    replaceSkus(id, p.skus == null ? List.of() : p.skus);
    replaceParameters(id, p.parameters == null ? List.of() : p.parameters);
  }

  /** 执行 replaceSkus 相关操作。 */
  private void replaceSkus(long pid, List<SkuDTO> ss) {
    // 1. 接收并整理 replaceSkus 的业务请求。
    // 2. 执行 replaceSkus 的核心业务校验与状态处理。
    // 3. 返回 replaceSkus 的处理结果。
    for (SkuDTO s : ss) {
      if (s == null) throw new BizException(ErrorCodes.INVALID, "SKU参数无效", 400);
      if (s.id == null) s.id = id();
      productSqlMapper.saveSku(pid, s, writeSpecJson(s.specJson), now());
    }
  }

  /** 执行 replaceParameters 相关操作。 */
  private void replaceParameters(long pid, List<ProductParameterDTO> ps) {
    // 1. 接收并整理 replaceParameters 的业务请求。
    // 2. 执行 replaceParameters 的核心业务校验与状态处理。
    // 3. 返回 replaceParameters 的处理结果。
    for (ProductParameterDTO p : ps) {
      if (p == null || p.name == null || p.name.isBlank() || p.value == null)
        throw new BizException(ErrorCodes.INVALID, "商品参数无效", 400);
    }
    productSqlMapper.deleteParameters(pid);
    for (ProductParameterDTO p : ps) productSqlMapper.insertParameter(pid, p, now());
  }

  /** 执行 readStatus 相关操作。 */
  private int readStatus(long id) {
    // 1. 接收并整理 readStatus 的业务请求。
    // 2. 执行 readStatus 的核心业务校验与状态处理。
    // 3. 返回 readStatus 的处理结果。
    return requireProduct(id).status;
  }

  /** 执行 event 相关操作。 */
  private void event(String type, long id) {
    // 1. 接收并整理 event 的业务请求。
    // 2. 执行 event 的核心业务校验与状态处理。
    // 3. 返回 event 的处理结果。
    try {
      rabbit.convertAndSend(
          "cloudmall.product.exchange", "product.changed", eventEnvelope(type, id));
    } catch (Exception e) {
      log.error("商品事件发送失败，事件类型={}，商品/分类ID={}；事务将回滚，Rabbit 消费端重试/DLX继续负责消费失败处理", type, id, e);
      throw new ProductEventPublishException(type, id, e);
    }
  }

  public static final class ProductEventPublishException extends RuntimeException {
    ProductEventPublishException(String type, long id, Exception cause) {
      super("商品事件发送失败，事件类型=" + type + "，商品/分类ID=" + id, cause);
    }
  }

  public static Map<String, Object> eventEnvelope(String type, long id) {
    String entityType = type.startsWith("CATEGORY") ? "CATEGORY" : "PRODUCT";
    String businessKeyName = "CATEGORY".equals(entityType) ? "categoryId" : "productId";
    OffsetDateTime occurredAt = now();
    String eventId = UUID.randomUUID().toString();
    Map<String, Object> payload = Map.of(businessKeyName, id);
    return Map.of(
        "eventId",
        eventId,
        "eventType",
        type,
        "occurredAt",
        occurredAt.toString(),
        "businessKey",
        Map.of("name", businessKeyName, "value", id),
        businessKeyName,
        id,
        "traceId",
        traceId(eventId),
        "payload",
        payload);
  }

  /** 执行 traceId 相关操作。 */
  private static String traceId(String eventId) {
    // 1. 接收并整理 traceId 的业务请求。
    // 2. 执行 traceId 的核心业务校验与状态处理。
    // 3. 返回 traceId 的处理结果。
    for (String key : List.of("traceId", "trace_id", "X-B3-TraceId")) {
      String value = MDC.get(key);
      if (value != null && !value.isBlank()) return value;
    }
    return "cloudmall-product-" + eventId;
  }

  /** 执行 writeSpecJson 相关操作。 */
  private String writeSpecJson(Map<String, String> specJson) {
    // 1. 接收并整理 writeSpecJson 的业务请求。
    // 2. 执行 writeSpecJson 的核心业务校验与状态处理。
    // 3. 返回 writeSpecJson 的处理结果。
    try {
      return objectMapper.writeValueAsString(specJson == null ? Collections.emptyMap() : specJson);
    } catch (JsonProcessingException e) {
      throw new BizException(ErrorCodes.INVALID, "SKU规格格式错误", 400);
    }
  }

  /** 执行 readSpecJson 相关操作。 */
  private Map<String, String> readSpecJson(Object value) {
    // 1. 接收并整理 readSpecJson 的业务请求。
    // 2. 执行 readSpecJson 的核心业务校验与状态处理。
    // 3. 返回 readSpecJson 的处理结果。
    try {
      JsonNode node = objectMapper.readTree(value == null ? "{}" : String.valueOf(value));
      if (!node.isObject()) throw new BizException(ErrorCodes.INVALID, "SKU规格格式错误", 400);
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext())
        if (!fields.next().getValue().isTextual())
          throw new BizException(ErrorCodes.INVALID, "SKU规格格式错误", 400);
      return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new BizException(ErrorCodes.INVALID, "SKU规格格式错误", 400);
    }
  }

  /** 执行 now 相关操作。 */
  private static OffsetDateTime now() {
    // 1. 接收并整理 now 的业务请求。
    // 2. 执行 now 的核心业务校验与状态处理。
    // 3. 返回 now 的处理结果。
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }

  /** 执行 ts 相关操作。 */
  private static java.sql.Timestamp ts(OffsetDateTime x) {
    // 1. 接收并整理 ts 的业务请求。
    // 2. 执行 ts 的核心业务校验与状态处理。
    // 3. 返回 ts 的处理结果。
    return java.sql.Timestamp.from(x.toInstant());
  }

  /** 执行 id 相关操作。 */
  private static long id() {
    // 1. 接收并整理 id 的业务请求。
    // 2. 执行 id 的核心业务校验与状态处理。
    // 3. 返回 id 的处理结果。
    return Math.abs(UUID.randomUUID().getMostSignificantBits());
  }

  /** 执行 money 相关操作。 */
  private static String money(String x) {
    // 1. 接收并整理 money 的业务请求。
    // 2. 执行 money 的核心业务校验与状态处理。
    // 3. 返回 money 的处理结果。
    try {
      return new BigDecimal(x == null ? "0" : x).setScale(2).toPlainString();
    } catch (Exception e) {
      throw new BizException(ErrorCodes.INVALID, "金额格式错误", 400);
    }
  }

  /** 执行 money 相关操作。 */
  private static String money(BigDecimal x) {
    // 1. 接收并整理 money 的业务请求。
    // 2. 执行 money 的核心业务校验与状态处理。
    // 3. 返回 money 的处理结果。
    return x.setScale(2).toPlainString();
  }

  /** 执行 secondsUntil 相关操作。 */
  private static long secondsUntil(Object value) {
    // 1. 接收并整理 secondsUntil 的业务请求。
    // 2. 执行 secondsUntil 的核心业务校验与状态处理。
    // 3. 返回 secondsUntil 的处理结果。
    if (value instanceof java.sql.Timestamp t)
      return Math.max(
          1, Duration.between(now(), t.toInstant().atOffset(ZoneOffset.ofHours(8))).getSeconds());
    try {
      return Math.max(
          1, Duration.between(now(), OffsetDateTime.parse(String.valueOf(value))).getSeconds());
    } catch (Exception e) {
      return 1;
    }
  }

  /** 执行 activityRecord 相关操作。 */
  private ActivityRecord activityRecord(Long id) {
    // 1. 接收并整理 activityRecord 的业务请求。
    // 2. 执行 activityRecord 的核心业务校验与状态处理。
    // 3. 返回 activityRecord 的处理结果。
    List<ActivityRecord> x =
        productSqlMapper.findActivity(id).stream()
            .map(
                row ->
                    new ActivityRecord(
                        ((Number) row.get("id")).longValue(),
                        ((Number) row.get("sku_id")).longValue(),
                        (java.sql.Timestamp) row.get("start_at"),
                        (java.sql.Timestamp) row.get("end_at"),
                        ((Number) row.get("stock_limit")).intValue(),
                        ((Number) row.get("per_user_limit")).intValue(),
                        String.valueOf(row.get("status"))))
            .toList();
    if (x.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "秒杀活动不存在", 404);
    return x.get(0);
  }

  /** 执行 toActivityResponse 相关操作。 */
  private ActivityVO toActivityResponse(ActivityRecord a) {
    // 1. 接收并整理 toActivityResponse 的业务请求。
    // 2. 执行 toActivityResponse 的核心业务校验与状态处理。
    // 3. 返回 toActivityResponse 的处理结果。
    String stock = redis.opsForValue().get("seckill:stock:" + a.id + ":" + a.skuId);
    return new ActivityVO(
        a.id,
        a.skuId,
        toOffset(a.startAt),
        toOffset(a.endAt),
        resolveRemainingStock(stock, a.status, toOffset(a.endAt), now()),
        a.perUserLimit,
        a.status);
  }

  public static int resolveRemainingStock(
      String redisValue, String status, OffsetDateTime endAt, OffsetDateTime currentTime) {
    if (endAt == null
        || !endAt.isAfter(currentTime)
        || "ENDED".equals(status)
        || "CLOSED".equals(status)) return 0;
    if (redisValue == null) return 0;
    try {
      return Math.max(0, Integer.parseInt(redisValue));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** 执行 toOffset 相关操作。 */
  private static OffsetDateTime toOffset(java.sql.Timestamp value) {
    // 1. 接收并整理 toOffset 的业务请求。
    // 2. 执行 toOffset 的核心业务校验与状态处理。
    // 3. 返回 toOffset 的处理结果。
    return value.toInstant().atOffset(ZoneOffset.ofHours(8));
  }

  /** 执行 validateCategory 相关操作。 */
  private static void validateCategory(CategoryDTO c) {
    // 1. 接收并整理 validateCategory 的业务请求。
    // 2. 执行 validateCategory 的核心业务校验与状态处理。
    // 3. 返回 validateCategory 的处理结果。
    if (c == null || c.name == null || c.name.isBlank())
      throw new BizException(ErrorCodes.INVALID, "分类名称不能为空", 400);
    if (c.parentId == null) c.parentId = 0L;
  }

  /** 执行 validateProduct 相关操作。 */
  private static void validateProduct(ProductDTO p) {
    // 1. 接收并整理 validateProduct 的业务请求。
    // 2. 执行 validateProduct 的核心业务校验与状态处理。
    // 3. 返回 validateProduct 的处理结果。
    if (p == null || p.name == null || p.name.isBlank())
      throw new BizException(ErrorCodes.INVALID, "商品名称不能为空", 400);
    money(p.price);
    if (p.categoryId == null) p.categoryId = 0L;
  }

  /** 执行 validateActivity 相关操作。 */
  private static void validateActivity(ActivityDTO a) {
    // 1. 接收并整理 validateActivity 的业务请求。
    // 2. 执行 validateActivity 的核心业务校验与状态处理。
    // 3. 返回 validateActivity 的处理结果。
    if (a == null
        || a.skuId == null
        || a.startAt == null
        || a.endAt == null
        || !a.endAt.isAfter(a.startAt)
        || a.stockLimit < 1
        || a.perUserLimit < 1) throw new BizException(ErrorCodes.INVALID, "秒杀活动参数无效", 400);
  }

  private record ActivityRecord(
      long id,
      long skuId,
      java.sql.Timestamp startAt,
      java.sql.Timestamp endAt,
      int stockLimit,
      int perUserLimit,
      String status) {}
}
