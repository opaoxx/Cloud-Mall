package com.cloudmall.pay.mapper;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 支付记录和回调日志兼容性 SQL 的统一持久化入口。 */
@Repository
public class PaySqlMapper {
  /** Spring JDBC 执行器。 */
  private final JdbcTemplate jdbcTemplate;

  /** 创建支付 SQL Mapper。 */
  public PaySqlMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 执行支付查询。 */
  public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
    return jdbcTemplate.query(sql, rowMapper, arguments);
  }

  /** 执行支付写操作。 */
  public int update(String sql, Object... arguments) {
    return jdbcTemplate.update(sql, arguments);
  }
}
