package com.cloudmall.product.mapper;

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
  public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
    return jdbcTemplate.query(sql, rowMapper, arguments);
  }

  /** 使用参数数组执行商品查询。 */
  public <T> List<T> query(String sql, Object[] arguments, RowMapper<T> rowMapper) {
    return jdbcTemplate.query(sql, rowMapper, arguments);
  }

  /** 查询商品单值。 */
  public <T> T queryForObject(String sql, Class<T> resultType, Object... arguments) {
    return jdbcTemplate.queryForObject(sql, resultType, arguments);
  }

  /** 使用参数数组查询商品单值。 */
  public <T> T queryForObject(String sql, Object[] arguments, Class<T> resultType) {
    return jdbcTemplate.queryForObject(sql, resultType, arguments);
  }

  /** 查询商品行集合。 */
  public List<Map<String, Object>> queryForList(String sql, Object... arguments) {
    return jdbcTemplate.queryForList(sql, arguments);
  }

  /** 执行商品写操作。 */
  public int update(String sql, Object... arguments) {
    return jdbcTemplate.update(sql, arguments);
  }
}
