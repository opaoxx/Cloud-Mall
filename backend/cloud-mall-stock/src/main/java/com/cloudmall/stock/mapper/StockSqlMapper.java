package com.cloudmall.stock.mapper;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 库存条件更新和流水 SQL 的统一持久化入口。 */
@Repository
public class StockSqlMapper {
  /** Spring JDBC 执行器。 */
  private final JdbcTemplate jdbcTemplate;

  /** 创建库存 SQL Mapper。 */
  public StockSqlMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 执行库存查询。 */
  public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
    return jdbcTemplate.query(sql, rowMapper, arguments);
  }

  /** 查询库存行集合。 */
  public List<Map<String, Object>> queryForList(String sql, Object... arguments) {
    return jdbcTemplate.queryForList(sql, arguments);
  }

  /** 查询库存单值。 */
  public <T> T queryForObject(String sql, Class<T> resultType, Object... arguments) {
    return jdbcTemplate.queryForObject(sql, resultType, arguments);
  }

  /** 执行库存写操作。 */
  public int update(String sql, Object... arguments) {
    return jdbcTemplate.update(sql, arguments);
  }

  /** 执行库存 DDL。 */
  public void execute(String sql) {
    jdbcTemplate.execute(sql);
  }
}
