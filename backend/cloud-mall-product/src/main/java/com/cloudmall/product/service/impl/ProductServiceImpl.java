package com.cloudmall.product.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.api.PageResult;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.product.mapper.ProductSqlMapper;
import com.cloudmall.product.service.ProductService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
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
    StringBuilder s =
        new StringBuilder(
            "select id,parent_id,name,sort_no,status from product_category where 1=1");
    List<Object> a = new ArrayList<>();
    if (parentId != null) {
      s.append(" and parent_id=?");
      a.add(parentId);
    }
    if (status != null) {
      s.append(" and status=?");
      a.add(status);
    }
    s.append(" order by sort_no,id");
    return ApiResponse.ok(
        productSqlMapper.query(
            s.toString(),
            a.toArray(),
            (r, n) ->
                new Category(
                    r.getLong("id"),
                    r.getLong("parent_id"),
                    r.getString("name"),
                    r.getInt("sort_no"),
                    r.getInt("status"))));
  }

  @Transactional
  @PostMapping("/categories")
  /** 执行 addCategory 相关操作。 */
  public ApiResponse<?> addCategory(@RequestBody Category c) {
    // 1. 接收并整理 addCategory 的业务请求。
    // 2. 执行 addCategory 的核心业务校验与状态处理。
    // 3. 返回 addCategory 的处理结果。
    AuthContext.requireAdmin();
    validateCategory(c);
    long id = id();
    productSqlMapper.update(
        "insert into product_category(id,parent_id,name,sort_no,status,created_at,updated_at)"
            + " values(?,?,?,?,1,?,?)",
        id,
        c.parentId,
        c.name,
        c.sortNo,
        now(),
        now());
    c.id = id;
    c.status = 1;
    event("CATEGORY_CHANGED", id);
    return ApiResponse.ok(c);
  }

  @Transactional
  @PutMapping("/categories/{id}")
  /** 执行 updateCategory 相关操作。 */
  public ApiResponse<?> updateCategory(@PathVariable Long id, @RequestBody Category c) {
    // 1. 接收并整理 updateCategory 的业务请求。
    // 2. 执行 updateCategory 的核心业务校验与状态处理。
    // 3. 返回 updateCategory 的处理结果。
    AuthContext.requireAdmin();
    validateCategory(c);
    requireCategory(id);
    productSqlMapper.update(
        "update product_category set parent_id=?,name=?,sort_no=?,updated_at=? where id=?",
        c.parentId,
        c.name,
        c.sortNo,
        now(),
        id);
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
    productSqlMapper.update(
        "update product_category set status=0,updated_at=? where id=?", now(), id);
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
    StringBuilder w = new StringBuilder(" where 1=1");
    List<Object> a = new ArrayList<>();
    if (keyword != null && !keyword.isBlank()) {
      w.append(" and name like ?");
      a.add("%" + keyword + "%");
    }
    if (categoryId != null) {
      w.append(" and category_id=?");
      a.add(categoryId);
    }
    if (status != null) {
      w.append(" and status=?");
      a.add(status);
    }
    long total =
        productSqlMapper.queryForObject(
            "select count(*) from product" + w, a.toArray(), Long.class);
    List<Object> pa = new ArrayList<>(a);
    pa.add((page - 1) * pageSize);
    pa.add(pageSize);
    List<Product> items =
        productSqlMapper.query(
            "select id,category_id,name,main_image,description,price,status from product"
                + w
                + " order by updated_at desc,id desc limit ?,?",
            pa.toArray(),
            (r, n) ->
                read(
                    r.getLong("id"),
                    r.getLong("category_id"),
                    r.getString("name"),
                    r.getString("main_image"),
                    r.getString("description"),
                    r.getBigDecimal("price"),
                    r.getInt("status")));
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
    List<Map<String, Object>> x =
        productSqlMapper.queryForList(
            "select s.id"
                + " sku_id,s.product_id,s.sku_code,s.spec_json,s.price,s.status,p.name,p.status"
                + " product_status from product_sku s join product p on p.id=s.product_id where"
                + " s.id=?",
            skuId);
    if (x.isEmpty()) throw new BizException("PRODUCT_SKU_NOT_FOUND", "SKU不存在", 404);
    Map<String, Object> r = x.get(0);
    if (((Number) r.get("product_status")).intValue() != 1
        || ((Number) r.get("status")).intValue() != 1)
      throw new BizException("PRODUCT_OFF_SHELF", "商品已下架", 409);
    return ApiResponse.ok(
        Map.of(
            "skuId",
            r.get("sku_id"),
            "productId",
            r.get("product_id"),
            "productName",
            r.get("name"),
            "skuCode",
            r.get("sku_code"),
            "unitPrice",
            money((BigDecimal) r.get("price")),
            "skuSnapshot",
            readSpecJson(r.get("spec_json"))));
  }

  @GetMapping("/products/{id}/hot-stat")
  /** 执行 hot 相关操作。 */
  public ApiResponse<?> hot(@PathVariable Long id) {
    // 1. 接收并整理 hot 的业务请求。
    // 2. 执行 hot 的核心业务校验与状态处理。
    // 3. 返回 hot 的处理结果。
    requireProduct(id);
    List<HotStatResponse> x =
        productSqlMapper.query(
            "select product_id,view_count,search_count,hot_score from product_hot_stat where"
                + " product_id=?",
            (r, n) ->
                new HotStatResponse(
                    r.getLong("product_id"),
                    r.getLong("view_count"),
                    r.getLong("search_count"),
                    r.getBigDecimal("hot_score") == null
                        ? "0.000000"
                        : r.getBigDecimal("hot_score").toPlainString()),
            id);
    return ApiResponse.ok(x.isEmpty() ? new HotStatResponse(id, 0, 0, "0.000000") : x.get(0));
  }

  @Transactional
  @PostMapping("/products")
  /** 执行 create 相关操作。 */
  public ApiResponse<?> create(@Valid @RequestBody Product p) {
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
  public ApiResponse<?> update(@PathVariable Long id, @RequestBody Product p) {
    // 1. 接收并整理 update 的业务请求。
    // 2. 执行 update 的核心业务校验与状态处理。
    // 3. 返回 update 的处理结果。
    AuthContext.requireAdmin();
    validateProduct(p);
    requireProduct(id);
    productSqlMapper.update(
        "update product set category_id=?,name=?,main_image=?,description=?,price=?,updated_at=?"
            + " where id=?",
        p.categoryId,
        p.name,
        p.mainImage,
        p.description,
        money(p.price),
        now(),
        id);
    if (p.skus != null) replaceSkus(id, p.skus);
    if (p.parameters != null) replaceParameters(id, p.parameters);
    Product saved = requireProduct(id);
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
  public ApiResponse<?> createActivity(@RequestBody Activity a) {
    // 1. 接收并整理 createActivity 的业务请求。
    // 2. 执行 createActivity 的核心业务校验与状态处理。
    // 3. 返回 createActivity 的处理结果。
    AuthContext.requireAdmin();
    validateActivity(a);
    long id = id();
    productSqlMapper.update(
        "insert into"
            + " seckill_activity(id,sku_id,start_at,end_at,stock_limit,per_user_limit,status,created_at,updated_at)"
            + " values(?,?,?,?,?,?, 'DRAFT',?,?)",
        id,
        a.skuId,
        ts(a.startAt),
        ts(a.endAt),
        a.stockLimit,
        a.perUserLimit,
        ts(now()),
        ts(now()));
    return activity(id);
  }

  @PutMapping("/seckill/activities/{id}")
  /** 执行 updateActivity 相关操作。 */
  public ApiResponse<?> updateActivity(@PathVariable Long id, @RequestBody Activity a) {
    // 1. 接收并整理 updateActivity 的业务请求。
    // 2. 执行 updateActivity 的核心业务校验与状态处理。
    // 3. 返回 updateActivity 的处理结果。
    AuthContext.requireAdmin();
    validateActivity(a);
    activity(id);
    productSqlMapper.update(
        "update seckill_activity set"
            + " sku_id=?,start_at=?,end_at=?,stock_limit=?,per_user_limit=?,updated_at=? where"
            + " id=?",
        a.skuId,
        ts(a.startAt),
        ts(a.endAt),
        a.stockLimit,
        a.perUserLimit,
        ts(now()),
        id);
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
    productSqlMapper.update(
        "update seckill_activity set status='PUBLISHED',updated_at=? where id=?", ts(now()), id);
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
    productSqlMapper.update(
        "update seckill_activity set status='STARTED',updated_at=? where id=?", ts(now()), id);
    return activity(id);
  }

  /** 执行 change 相关操作。 */
  private ApiResponse<?> change(Long id, int st) {
    // 1. 接收并整理 change 的业务请求。
    // 2. 执行 change 的核心业务校验与状态处理。
    // 3. 返回 change 的处理结果。
    Product p = requireProduct(id);
    productSqlMapper.update("update product set status=?,updated_at=? where id=?", st, now(), id);
    p.status = st;
    p.published = st == 1;
    event("PRODUCT_CHANGED", id);
    return ApiResponse.ok(p);
  }

  /** 执行 requireProduct 相关操作。 */
  private Product requireProduct(Long id) {
    // 1. 接收并整理 requireProduct 的业务请求。
    // 2. 执行 requireProduct 的核心业务校验与状态处理。
    // 3. 返回 requireProduct 的处理结果。
    List<Product> x =
        productSqlMapper.query(
            "select id,category_id,name,main_image,description,price,status from product where"
                + " id=?",
            (r, n) ->
                read(
                    r.getLong("id"),
                    r.getLong("category_id"),
                    r.getString("name"),
                    r.getString("main_image"),
                    r.getString("description"),
                    r.getBigDecimal("price"),
                    r.getInt("status")),
            id);
    if (x.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "商品不存在", 404);
    return x.get(0);
  }

  /** 执行 requireCategory 相关操作。 */
  private void requireCategory(Long id) {
    // 1. 接收并整理 requireCategory 的业务请求。
    // 2. 执行 requireCategory 的核心业务校验与状态处理。
    // 3. 返回 requireCategory 的处理结果。
    if (productSqlMapper.queryForObject(
            "select count(*) from product_category where id=?", Long.class, id)
        == 0) throw new BizException(ErrorCodes.NOT_FOUND, "分类不存在", 404);
  }

  /** 执行 read 相关操作。 */
  private Product read(
      long id, long cat, String n, String image, String desc, BigDecimal price, int st) {
    // 1. 接收并整理 read 的业务请求。
    // 2. 执行 read 的核心业务校验与状态处理。
    // 3. 返回 read 的处理结果。
    Product p = new Product();
    p.id = id;
    p.categoryId = cat;
    p.name = n;
    p.mainImage = image;
    p.description = desc;
    p.price = money(price);
    p.status = st;
    p.published = st == 1;
    p.skus =
        productSqlMapper.query(
            "select id,product_id,sku_code,spec_json,price,status from product_sku where"
                + " product_id=? order by id",
            (r, z) ->
                new Sku(
                    r.getLong("id"),
                    r.getLong("product_id"),
                    r.getString("sku_code"),
                    readSpecJson(r.getString("spec_json")),
                    money(r.getBigDecimal("price")),
                    r.getInt("status") == 1),
            id);
    p.parameters =
        productSqlMapper.query(
            "select id,product_id,param_name,param_value,sort_no from product_parameter where"
                + " product_id=? order by sort_no,id",
            (r, z) ->
                new ProductParameter(
                    r.getLong("id"),
                    r.getLong("product_id"),
                    r.getString("param_name"),
                    r.getString("param_value"),
                    r.getInt("sort_no")),
            id);
    return p;
  }

  /** 执行 save 相关操作。 */
  private void save(long id, Product p) {
    // 1. 接收并整理 save 的业务请求。
    // 2. 执行 save 的核心业务校验与状态处理。
    // 3. 返回 save 的处理结果。
    productSqlMapper.update(
        "insert into"
            + " product(id,category_id,name,main_image,description,price,status,version,created_at,updated_at)"
            + " values(?,?,?,?,?,?,0,0,?,?)",
        id,
        p.categoryId,
        p.name,
        p.mainImage,
        p.description,
        money(p.price),
        now(),
        now());
    replaceSkus(id, p.skus == null ? List.of() : p.skus);
    replaceParameters(id, p.parameters == null ? List.of() : p.parameters);
  }

  /** 执行 replaceSkus 相关操作。 */
  private void replaceSkus(long pid, List<Sku> ss) {
    // 1. 接收并整理 replaceSkus 的业务请求。
    // 2. 执行 replaceSkus 的核心业务校验与状态处理。
    // 3. 返回 replaceSkus 的处理结果。
    for (Sku s : ss) {
      if (s == null) throw new BizException(ErrorCodes.INVALID, "SKU参数无效", 400);
      if (s.id == null) s.id = id();
      productSqlMapper.update(
          "insert into"
              + " product_sku(id,product_id,sku_code,spec_json,price,status,created_at,updated_at)"
              + " values(?,?,?,?,?,1,?,?) on duplicate key update"
              + " price=values(price),spec_json=values(spec_json),status=values(status),updated_at=values(updated_at)",
          s.id,
          pid,
          s.skuCode,
          writeSpecJson(s.specJson),
          money(s.price),
          now(),
          now());
    }
  }

  /** 执行 replaceParameters 相关操作。 */
  private void replaceParameters(long pid, List<ProductParameter> ps) {
    // 1. 接收并整理 replaceParameters 的业务请求。
    // 2. 执行 replaceParameters 的核心业务校验与状态处理。
    // 3. 返回 replaceParameters 的处理结果。
    for (ProductParameter p : ps) {
      if (p == null || p.name == null || p.name.isBlank() || p.value == null)
        throw new BizException(ErrorCodes.INVALID, "商品参数无效", 400);
    }
    productSqlMapper.update("delete from product_parameter where product_id=?", pid);
    for (ProductParameter p : ps)
      productSqlMapper.update(
          "insert into"
              + " product_parameter(id,product_id,param_name,param_value,sort_no,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?)",
          id(),
          pid,
          p.name,
          p.value,
          p.sortNo,
          now(),
          now());
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

  static final class ProductEventPublishException extends RuntimeException {
    ProductEventPublishException(String type, long id, Exception cause) {
      super("商品事件发送失败，事件类型=" + type + "，商品/分类ID=" + id, cause);
    }
  }

  static Map<String, Object> eventEnvelope(String type, long id) {
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
        productSqlMapper.query(
            "select id,sku_id,start_at,end_at,stock_limit,per_user_limit,status from"
                + " seckill_activity where id=?",
            (r, n) ->
                new ActivityRecord(
                    r.getLong("id"),
                    r.getLong("sku_id"),
                    r.getTimestamp("start_at"),
                    r.getTimestamp("end_at"),
                    r.getInt("stock_limit"),
                    r.getInt("per_user_limit"),
                    r.getString("status")),
            id);
    if (x.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "秒杀活动不存在", 404);
    return x.get(0);
  }

  /** 执行 toActivityResponse 相关操作。 */
  private ActivityResponse toActivityResponse(ActivityRecord a) {
    // 1. 接收并整理 toActivityResponse 的业务请求。
    // 2. 执行 toActivityResponse 的核心业务校验与状态处理。
    // 3. 返回 toActivityResponse 的处理结果。
    String stock = redis.opsForValue().get("seckill:stock:" + a.id + ":" + a.skuId);
    return new ActivityResponse(
        a.id,
        a.skuId,
        toOffset(a.startAt),
        toOffset(a.endAt),
        resolveRemainingStock(stock, a.status, toOffset(a.endAt), now()),
        a.perUserLimit,
        a.status);
  }

  static int resolveRemainingStock(
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
  private static void validateCategory(Category c) {
    // 1. 接收并整理 validateCategory 的业务请求。
    // 2. 执行 validateCategory 的核心业务校验与状态处理。
    // 3. 返回 validateCategory 的处理结果。
    if (c == null || c.name == null || c.name.isBlank())
      throw new BizException(ErrorCodes.INVALID, "分类名称不能为空", 400);
    if (c.parentId == null) c.parentId = 0L;
  }

  /** 执行 validateProduct 相关操作。 */
  private static void validateProduct(Product p) {
    // 1. 接收并整理 validateProduct 的业务请求。
    // 2. 执行 validateProduct 的核心业务校验与状态处理。
    // 3. 返回 validateProduct 的处理结果。
    if (p == null || p.name == null || p.name.isBlank())
      throw new BizException(ErrorCodes.INVALID, "商品名称不能为空", 400);
    money(p.price);
    if (p.categoryId == null) p.categoryId = 0L;
  }

  /** 执行 validateActivity 相关操作。 */
  private static void validateActivity(Activity a) {
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

  public static class Product {
    @JsonSerialize(using = ToStringSerializer.class)
    /** 保存 id 的业务状态或配置。 */
    public Long id;

    /** 保存 categoryId 的业务状态或配置。 */
    public Long categoryId = 0L;

    @NotBlank public String name;

    /** 保存 price 的业务状态或配置。 */
    public String mainImage, description, price;

    /** 保存 published 的业务状态或配置。 */
    public boolean published;

    /** 保存 status 的业务状态或配置。 */
    public int status;

    /** 执行 业务操作 相关操作。 */
    public List<Sku> skus = new ArrayList<>();

    /** 保存 parameters 的业务状态或配置。 */
    public List<ProductParameter> parameters;
  }

  public static class Sku {
    /** 保存 id 的业务状态或配置。 */
    public Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    /** 保存 productId 的业务状态或配置。 */
    public Long productId;

    /** 保存 price 的业务状态或配置。 */
    public String skuCode, price;

    /** 执行 业务操作 相关操作。 */
    public Map<String, String> specJson = new LinkedHashMap<>();

    /** 保存 status 的业务状态或配置。 */
    public boolean status;

    /** 执行 Sku 相关操作。 */
    public Sku() {}

    // 1. 接收并整理 Sku 的业务请求。
    // 2. 执行 Sku 的核心业务校验与状态处理。
    // 3. 返回 Sku 的处理结果。

    Sku(Long i, Long p, String c, Map<String, String> j, String v, boolean s) {
      id = i;
      productId = p;
      skuCode = c;
      specJson = j;
      price = v;
      status = s;
    }
  }

  public static class ProductParameter {
    /** 保存 id 的业务状态或配置。 */
    public Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    /** 保存 productId 的业务状态或配置。 */
    public Long productId;

    /** 保存 value 的业务状态或配置。 */
    public String name, value;

    /** 保存 sortNo 的业务状态或配置。 */
    public int sortNo;

    /** 执行 ProductParameter 相关操作。 */
    public ProductParameter() {}

    // 1. 接收并整理 ProductParameter 的业务请求。
    // 2. 执行 ProductParameter 的核心业务校验与状态处理。
    // 3. 返回 ProductParameter 的处理结果。

    ProductParameter(Long i, Long p, String n, String v, int s) {
      id = i;
      productId = p;
      name = n;
      value = v;
      sortNo = s;
    }
  }

  public static class Category {
    /** 保存 parentId 的业务状态或配置。 */
    public Long id, parentId = 0L;

    @NotBlank public String name;

    /** 保存 status 的业务状态或配置。 */
    public int sortNo, status;

    Category() {}

    Category(long i, long p, String n, int s, int st) {
      id = i;
      parentId = p;
      name = n;
      sortNo = s;
      status = st;
    }
  }

  public static class Activity {
    /** 保存 skuId 的业务状态或配置。 */
    public Long skuId;

    /** 保存 endAt 的业务状态或配置。 */
    public OffsetDateTime startAt, endAt;

    /** 保存 perUserLimit 的业务状态或配置。 */
    public int stockLimit, perUserLimit;
  }

  private record ActivityRecord(
      long id,
      long skuId,
      java.sql.Timestamp startAt,
      java.sql.Timestamp endAt,
      int stockLimit,
      int perUserLimit,
      String status) {}

  public static class ActivityResponse {
    /** 保存 skuId 的业务状态或配置。 */
    public final long activityId, skuId;

    /** 保存 endAt 的业务状态或配置。 */
    public final OffsetDateTime startAt, endAt;

    /** 保存 perUserLimit 的业务状态或配置。 */
    public final int remainingStock, perUserLimit;

    /** 保存 status 的业务状态或配置。 */
    public final String status;

    ActivityResponse(long a, long s, OffsetDateTime st, OffsetDateTime e, int r, int p, String v) {
      activityId = a;
      skuId = s;
      startAt = st;
      endAt = e;
      remainingStock = r;
      perUserLimit = p;
      status = v;
    }
  }

  public static class HotStatResponse {
    /** 保存 searchCount 的业务状态或配置。 */
    public final long productId, viewCount, searchCount;

    /** 保存 hotScore 的业务状态或配置。 */
    public final String hotScore;

    HotStatResponse(long p, long v, long s, String h) {
      productId = p;
      viewCount = v;
      searchCount = s;
      hotScore = h;
    }
  }
}
