package com.cloudmall.order.mapper;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 订单动态分片和兼容性 SQL 的统一持久化入口。 */
@Repository
public class OrderSqlMapper {
  /** Spring JDBC 执行器。 */
  private final JdbcTemplate jdbcTemplate;

  /** 创建订单 SQL Mapper。 */
  public OrderSqlMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 执行订单查询。 */
  public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
    return jdbcTemplate.query(sql, rowMapper, arguments);
  }

  /** 使用参数数组执行订单查询。 */
  public <T> List<T> query(String sql, Object[] arguments, RowMapper<T> rowMapper) {
    return jdbcTemplate.query(sql, arguments, rowMapper);
  }

  /** 查询订单单值。 */
  public <T> T queryForObject(String sql, Class<T> resultType, Object... arguments) {
    return jdbcTemplate.queryForObject(sql, resultType, arguments);
  }

  /** 查询订单行集合。 */
  public List<Map<String, Object>> queryForList(String sql, Object... arguments) {
    return jdbcTemplate.queryForList(sql, arguments);
  }

  /** 执行订单写操作。 */
  public int update(String sql, Object... arguments) {
    return jdbcTemplate.update(sql, arguments);
  }

  /** 执行订单 DDL。 */
  public void execute(String sql) {
    jdbcTemplate.execute(sql);
  }
}
