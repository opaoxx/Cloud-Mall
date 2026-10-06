package com.cloudmall.product.util;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

/** 根据完整 cause 链区分连接获取、SQL 超时和通信故障，不把未知错误伪装为依赖失败。 */
public final class ProductReadFailureClassifier {
  /** 有界遍历防止损坏或循环异常链占用请求线程。 */
  private static final int MAX_CAUSE_DEPTH = 32;

  /** 内部分类及稳定的低基数指标标签，不暴露到 API 成功响应。 */
  public enum Reason {
    /** JDBC 连接获取失败，含只读事务开始时的连接池等待失败。 */
    CONNECTION_ACQUIRE("connection_acquire"),
    /** 已有 JDBC/DAO 证据的查询执行或响应超时。 */
    SQL_TIMEOUT("sql_timeout"),
    /** 查询期间连接不可恢复或通信链路中断。 */
    DB_CONNECTION("db_connection"),
    /** 未能证明为数据库依赖不可用的实现或其他异常。 */
    UNKNOWN("unknown");

    /** 既有监控指标的 reason 值。 */
    private final String metricTag;

    /** 为每个故障分类绑定稳定标签。 */
    Reason(String metricTag) {
      this.metricTag = metricTag;
    }

    /** 返回用于失败计数的稳定标签。 */
    public String metricTag() {
      return metricTag;
    }
  }

  /** 无状态工具类不允许实例化。 */
  private ProductReadFailureClassifier() {}

  /** 收集完整异常链后按证据优先级分类，包装层不能遮蔽内层 SQL timeout。 */
  public static Reason classify(Throwable failure) {
    // 1. 身份集合防循环；未知或非 JDBC 依赖故障不能误记为数据库故障。
    List<Throwable> causes = new ArrayList<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = failure;
        cause != null && causes.size() < MAX_CAUSE_DEPTH;
        cause = cause.getCause()) {
      if (!seen.add(cause)) return Reason.UNKNOWN;
      causes.add(cause);
    }
    if (causes.size() == MAX_CAUSE_DEPTH && causes.get(MAX_CAUSE_DEPTH - 1).getCause() != null)
      return Reason.UNKNOWN;
    for (Throwable cause : causes) {
      String type = cause.getClass().getName();
      if (cause instanceof BadSqlGrammarException
          || type.startsWith("org.springframework.data.redis.")
          || type.startsWith("io.lettuce.")
          || type.startsWith("org.elasticsearch.")) return Reason.UNKNOWN;
    }
    // 2. 获取连接有明确框架/类型证据，区别于已经执行 SQL 时的连接中断。
    if (causes.stream().anyMatch(CannotGetJdbcConnectionException.class::isInstance))
      return Reason.CONNECTION_ACQUIRE;
    if (causes.stream().anyMatch(CannotCreateTransactionException.class::isInstance)
        && causes.stream().anyMatch(ProductReadFailureClassifier::hikariAcquisitionTimeout))
      return Reason.CONNECTION_ACQUIRE;
    // 3. SQL 超时优先于泛化的 recoverable/communication 包装。
    boolean queryContext =
        causes.stream().anyMatch(DataAccessException.class::isInstance)
            && causes.stream().noneMatch(CannotCreateTransactionException.class::isInstance);
    if (causes.stream().anyMatch(QueryTimeoutException.class::isInstance)
        || (queryContext && causes.stream().anyMatch(SQLTimeoutException.class::isInstance)))
      return Reason.SQL_TIMEOUT;
    if (queryContext && causes.stream().anyMatch(SocketTimeoutException.class::isInstance))
      return Reason.SQL_TIMEOUT;
    for (Throwable cause : causes) {
      if (queryContext
          && cause instanceof SQLException sql
          && ("HYT00".equals(sql.getSQLState()) || "HYT01".equals(sql.getSQLState())))
        return Reason.SQL_TIMEOUT;
    }
    if (causes.stream()
        .anyMatch(
            cause ->
                cause instanceof RecoverableDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof SQLTransientConnectionException
                    || cause instanceof SQLNonTransientConnectionException
                    || cause instanceof SQLRecoverableException)) return Reason.DB_CONNECTION;
    for (Throwable cause : causes) {
      if (cause instanceof SQLException sql
          && sql.getSQLState() != null
          && sql.getSQLState().startsWith("08")) return Reason.DB_CONNECTION;
    }
    return Reason.UNKNOWN;
  }

  /** 类型、事务开始上下文与稳定 Hikari 超时标识同时成立才记录获取连接等待失败。 */
  private static boolean hikariAcquisitionTimeout(Throwable failure) {
    if (!(failure instanceof SQLTransientConnectionException)) return false;
    String message = failure.getMessage();
    return message != null
        && message.contains("Connection is not available")
        && message.contains("request timed out");
  }
}
