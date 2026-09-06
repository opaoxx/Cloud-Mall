package com.cloudmall.product.mapper;

import com.cloudmall.product.domain.dto.ActivityDTO;
import com.cloudmall.product.domain.dto.CategoryDTO;
import com.cloudmall.product.domain.dto.ProductDTO;
import com.cloudmall.product.domain.dto.ProductParameterDTO;
import com.cloudmall.product.domain.dto.SkuDTO;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 商品复杂查询和兼容性 SQL 的统一持久化入口。 */
@Repository
public class ProductSqlMapper {
  /** Spring JDBC 执行器。 */
  private final JdbcTemplate jdbcTemplate;

  /** 创建商品 SQL Mapper。 */
  public ProductSqlMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 执行商品查询。 */
  private <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
    return jdbcTemplate.query(sql, rowMapper, arguments);
  }

  /** 使用参数数组执行商品查询。 */
  private <T> List<T> query(String sql, Object[] arguments, RowMapper<T> rowMapper) {
    return jdbcTemplate.query(sql, rowMapper, arguments);
  }

  /** 查询商品单值。 */
  private <T> T queryForObject(String sql, Class<T> resultType, Object... arguments) {
    return jdbcTemplate.queryForObject(sql, resultType, arguments);
  }

  /** 使用参数数组查询商品单值。 */
  private <T> T queryForObject(String sql, Object[] arguments, Class<T> resultType) {
    return jdbcTemplate.queryForObject(sql, resultType, arguments);
  }

  /** 查询商品行集合。 */
  private List<Map<String, Object>> queryForList(String sql, Object... arguments) {
    return jdbcTemplate.queryForList(sql, arguments);
  }

  /** 执行商品写操作。 */
  private int update(String sql, Object... arguments) {
    return jdbcTemplate.update(sql, arguments);
  }

  /** 查询商品分类。 */
  public List<Map<String, Object>> findCategories(Long parentId, Integer status) {
    StringBuilder sql =
        new StringBuilder(
            "select id,parent_id,name,sort_no,status from product_category where 1=1");
    List<Object> arguments = new java.util.ArrayList<>();
    if (parentId != null) {
      sql.append(" and parent_id=?");
      arguments.add(parentId);
    }
    if (status != null) {
      sql.append(" and status=?");
      arguments.add(status);
    }
    sql.append(" order by sort_no,id");
    return queryForList(sql.toString(), arguments.toArray());
  }

  /** 新增商品分类。 */
  public int insertCategory(long id, CategoryDTO category, Object occurredAt) {
    return update(
        "insert into product_category(id,parent_id,name,sort_no,status,created_at,updated_at)"
            + " values(?,?,?,?,1,?,?)",
        id,
        category.parentId,
        category.name,
        category.sortNo,
        occurredAt,
        occurredAt);
  }

  /** 更新商品分类。 */
  public int updateCategory(long id, CategoryDTO category, Object occurredAt) {
    return update(
        "update product_category set parent_id=?,name=?,sort_no=?,updated_at=? where id=?",
        category.parentId,
        category.name,
        category.sortNo,
        occurredAt,
        id);
  }

  /** 停用商品分类。 */
  public int disableCategory(long id, Object occurredAt) {
    return update("update product_category set status=0,updated_at=? where id=?", occurredAt, id);
  }

  /** 查询商品总数。 */
  public Long countProducts(String keyword, Long categoryId, Integer status) {
    StringBuilder condition = new StringBuilder(" where 1=1");
    List<Object> arguments = new java.util.ArrayList<>();
    appendProductConditions(condition, arguments, keyword, categoryId, status);
    return queryForObject(
        "select count(*) from product" + condition, arguments.toArray(), Long.class);
  }

  /** 分页查询商品。 */
  public List<Map<String, Object>> findProducts(
      String keyword, Long categoryId, Integer status, int offset, int pageSize) {
    StringBuilder condition = new StringBuilder(" where 1=1");
    List<Object> arguments = new java.util.ArrayList<>();
    appendProductConditions(condition, arguments, keyword, categoryId, status);
    arguments.add(offset);
    arguments.add(pageSize);
    return queryForList(
        "select id,category_id,name,main_image,description,price,status from product"
            + condition
            + " order by updated_at desc,id desc limit ?,?",
        arguments.toArray());
  }

  /** 查询 SKU 及其商品状态。 */
  public List<Map<String, Object>> findSku(Long skuId) {
    return queryForList(
        "select s.id sku_id,s.product_id,s.sku_code,s.spec_json,s.price,s.status,p.name,p.status"
            + " product_status from product_sku s join product p on p.id=s.product_id where s.id=?",
        skuId);
  }

  /** 查询商品热度统计。 */
  public List<Map<String, Object>> findHotStat(Long productId) {
    return queryForList(
        "select product_id,view_count,search_count,hot_score from product_hot_stat where"
            + " product_id=?",
        productId);
  }

  /** 更新商品基础信息。 */
  public int updateProduct(Long productId, ProductDTO product, String price, Object occurredAt) {
    return update(
        "update product set category_id=?,name=?,main_image=?,description=?,price=?,updated_at=?"
            + " where id=?",
        product.categoryId,
        product.name,
        product.mainImage,
        product.description,
        price,
        occurredAt,
        productId);
  }

  /** 新增商品基础信息。 */
  public int insertProduct(long productId, ProductDTO product, String price, Object occurredAt) {
    return update(
        "insert into"
            + " product(id,category_id,name,main_image,description,price,status,version,created_at,updated_at)"
            + " values(?,?,?,?,?,?,0,0,?,?)",
        productId,
        product.categoryId,
        product.name,
        product.mainImage,
        product.description,
        price,
        occurredAt,
        occurredAt);
  }

  /** 新增秒杀活动。 */
  public int insertActivity(long activityId, ActivityDTO activity, Object occurredAt) {
    return update(
        "insert into"
            + " seckill_activity(id,sku_id,start_at,end_at,stock_limit,per_user_limit,status,created_at,updated_at)"
            + " values(?,?,?,?,?,?, 'DRAFT',?,?)",
        activityId,
        activity.skuId,
        activity.startAt,
        activity.endAt,
        activity.stockLimit,
        activity.perUserLimit,
        occurredAt,
        occurredAt);
  }

  /** 更新秒杀活动。 */
  public int updateActivity(long activityId, ActivityDTO activity, Object occurredAt) {
    return update(
        "update seckill_activity set"
            + " sku_id=?,start_at=?,end_at=?,stock_limit=?,per_user_limit=?,updated_at=? where"
            + " id=?",
        activity.skuId,
        activity.startAt,
        activity.endAt,
        activity.stockLimit,
        activity.perUserLimit,
        occurredAt,
        activityId);
  }

  /** 更新秒杀活动状态。 */
  public int updateActivityStatus(long activityId, String status, Object occurredAt) {
    return update(
        "update seckill_activity set status=?,updated_at=? where id=?",
        status,
        occurredAt,
        activityId);
  }

  /** 更新商品上下架状态。 */
  public int updateProductStatus(long productId, int status, Object occurredAt) {
    return update(
        "update product set status=?,updated_at=? where id=?", status, occurredAt, productId);
  }

  /** 查询商品基础信息。 */
  public List<Map<String, Object>> findProduct(Long productId) {
    return queryForList(
        "select id,category_id,name,main_image,description,price,status from product where id=?",
        productId);
  }

  /** 判断商品分类是否存在。 */
  public Long countCategory(Long categoryId) {
    return queryForObject(
        "select count(*) from product_category where id=?", Long.class, categoryId);
  }

  /** 查询商品 SKU 列表。 */
  public List<Map<String, Object>> findSkus(Long productId) {
    return queryForList(
        "select id,product_id,sku_code,spec_json,price,status from product_sku where product_id=?"
            + " order by id",
        productId);
  }

  /** 查询商品参数列表。 */
  public List<Map<String, Object>> findParameters(Long productId) {
    return queryForList(
        "select id,product_id,param_name,param_value,sort_no from product_parameter where"
            + " product_id=? order by sort_no,id",
        productId);
  }

  /** 新增或更新 SKU。 */
  public int saveSku(long productId, SkuDTO sku, String specJson, Object occurredAt) {
    return update(
        "insert into"
            + " product_sku(id,product_id,sku_code,spec_json,price,status,created_at,updated_at)"
            + " values(?,?,?,?,?,1,?,?) on duplicate key update"
            + " price=values(price),spec_json=values(spec_json),status=values(status),updated_at=values(updated_at)",
        sku.id,
        productId,
        sku.skuCode,
        specJson,
        sku.price,
        occurredAt,
        occurredAt);
  }

  /** 删除商品全部参数。 */
  public int deleteParameters(long productId) {
    return update("delete from product_parameter where product_id=?", productId);
  }

  /** 新增商品参数。 */
  public int insertParameter(long productId, ProductParameterDTO parameter, Object occurredAt) {
    return update(
        "insert into"
            + " product_parameter(id,product_id,param_name,param_value,sort_no,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?)",
        Math.abs(java.util.UUID.randomUUID().getMostSignificantBits()),
        productId,
        parameter.name,
        parameter.value,
        parameter.sortNo,
        occurredAt,
        occurredAt);
  }

  /** 查询秒杀活动。 */
  public List<Map<String, Object>> findActivity(Long activityId) {
    return queryForList(
        "select id,sku_id,start_at,end_at,stock_limit,per_user_limit,status from seckill_activity"
            + " where id=?",
        activityId);
  }

  /** 追加商品列表筛选条件。 */
  private static void appendProductConditions(
      StringBuilder condition,
      List<Object> arguments,
      String keyword,
      Long categoryId,
      Integer status) {
    if (keyword != null && !keyword.isBlank()) {
      condition.append(" and name like ?");
      arguments.add("%" + keyword + "%");
    }
    if (categoryId != null) {
      condition.append(" and category_id=?");
      arguments.add(categoryId);
    }
    if (status != null) {
      condition.append(" and status=?");
      arguments.add(status);
    }
  }
}
