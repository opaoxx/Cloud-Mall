package com.cloudmall.stock.util;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.cloudmall.common.error.BizException;
import io.micrometer.core.instrument.Metrics;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 在纯 Redis 调用或完整数据库操作之外保护依赖，不重试、不猜测写入结果。 */
public final class StockDependencyGuard {
  /** 秒杀元数据、预扣 Lua 和受理 marker 的 Redis 操作。 */
  public static final String REDIS_SECKILL = "stock-redis-seckill-write";

  /** 普通库存缓存、校准、租约和秒杀释放回执的 Redis 操作。 */
  public static final String REDIS_PROJECTION = "stock-redis-projection";

  /** 包含实际提交/回滚的库存数据库事务。 */
  public static final String MYSQL_TRANSACTION = "stock-mysql-reservation";

  /** 只读库存事实查询，不包含已命中 Redis 的普通库存读。 */
  public static final String MYSQL_READ = "stock-mysql-read";

  /** 后台恢复批次，慢耗时衡量整批操作而非单条 JDBC。 */
  public static final String MYSQL_RECOVERY = "stock-mysql-recovery";

  /** 已评审的唯一依赖资源集合。 */
  private static final Set<String> RESOURCES =
      Set.of(REDIS_SECKILL, REDIS_PROJECTION, MYSQL_TRANSACTION, MYSQL_READ, MYSQL_RECOVERY);

  /** 当前线程已由外部完整数据库边界保护，嵌套 Bean 不在事务中途重复开 gate。 */
  private static final ThreadLocal<Boolean> MYSQL_ACTIVE = new ThreadLocal<>();

  /** 当前整操作的数据库 Entry，内部已捕获的连接故障仍需计分一次。 */
  private static final ThreadLocal<Entry> MYSQL_ENTRY = new ThreadLocal<>();

  /** 同一整操作只统计一次数据库依赖故障。 */
  private static final ThreadLocal<Boolean> MYSQL_FAILURE_RECORDED = new ThreadLocal<>();

  /** 同一个 Redis 操作中 Template 的内部委托不重复计分。 */
  private static final ThreadLocal<Boolean> REDIS_ACTIVE = new ThreadLocal<>();

  /** 逻辑 Redis 操作选择的资源，Template 子类继承该场景。 */
  private static final ThreadLocal<String> REDIS_SCENE = new ThreadLocal<>();

  /** 初始化校准与 DDL 不计入业务熔断，防止启动耗时触发业务兜底。 */
  private static final ThreadLocal<Boolean> INITIALIZING = new ThreadLocal<>();

  /** 工具类不允许实例化。 */
  private StockDependencyGuard() {}

  /** 可承载 Spring AOP proceed 的一次操作，不添加自动重试。 */
  @FunctionalInterface
  public interface Operation<T> {
    /** 执行原操作，并原样传播业务异常或事务异常。 */
    T run() throws Throwable;
  }

  /** 检查完整事务边界是否仍处于保护内，供事务 advisor 回归验证。 */
  public static boolean mysqlActive() {
    return Boolean.TRUE.equals(MYSQL_ACTIVE.get());
  }

  /** 返回当前 Redis 场景，非秒杀路径默认是可恢复投影。 */
  public static String redisResource() {
    return REDIS_SCENE.get() == null ? REDIS_PROJECTION : REDIS_SCENE.get();
  }

  /** 在纯 Redis supplier 外保护协议验证和实际调用，Template 内部委托不重复开 gate。 */
  public static <T> T redis(String resource, Supplier<T> operation) {
    String previous = REDIS_SCENE.get();
    REDIS_SCENE.set(resource);
    try {
      return call(resource, operation);
    } finally {
      if (previous == null) REDIS_SCENE.remove();
      else REDIS_SCENE.set(previous);
    }
  }

  /** 保护没有 checked 异常的依赖调用，所有实际写入只执行一次。 */
  public static <T> T call(String resource, Supplier<T> operation) {
    try {
      return execute(resource, operation::get);
    } catch (RuntimeException | Error failure) {
      throw failure;
    } catch (Throwable failure) {
      throw new IllegalStateException("依赖操作抛出了未声明异常", failure);
    }
  }

  /** 在事务 begin 前取得 Entry，直到 commit/rollback 结束后才退出。 */
  public static <T> T execute(String resource, Operation<T> operation) throws Throwable {
    if (!RESOURCES.contains(resource)) throw new IllegalArgumentException("未评审的 Stock 依赖资源");
    if (Boolean.TRUE.equals(INITIALIZING.get())) return operation.run();
    boolean mysql = resource.startsWith("stock-mysql-");
    ThreadLocal<Boolean> active = mysql ? MYSQL_ACTIVE : REDIS_ACTIVE;
    if (Boolean.TRUE.equals(active.get())
        || (mysql && TransactionSynchronizationManager.isActualTransactionActive())) {
      return operation.run();
    }
    // 1. 未取得 Entry 时绝不进入原操作，已有意图和回执保持原样。
    Entry entry;
    try {
      entry = SphU.entry(resource, EntryType.OUT);
    } catch (BlockException blocked) {
      Metrics.counter(
              "sentinel.degrade.block", "service", "stock", "resource", resource, "status", "503")
          .increment();
      throw unavailable(resource, blocked);
    }
    active.set(true);
    if (mysql) MYSQL_ENTRY.set(entry);
    try {
      // 2. 正常返回值包含业务 Lua 拒绝，不由 guard 解释为库存成功或系统故障。
      return operation.run();
    } catch (Throwable failure) {
      if (dependencyFailure(mysql, failure)) {
        if (mysql) noteMysqlFailure(failure);
        else {
          Tracer.traceEntry(failure, entry);
          Metrics.counter("stock.dependency.failure", "resource", resource).increment();
        }
        throw unavailable(resource, failure);
      }
      throw failure;
    } finally {
      // 3. 清理线程上下文并恰好退出一次，包含事务 commit 抛出的异常。
      active.remove();
      if (mysql) {
        MYSQL_ENTRY.remove();
        MYSQL_FAILURE_RECORDED.remove();
      }
      entry.exit();
    }
  }

  /** Lua 返回非法类型或回执冲突时记录协议失败，不能伪装售罄。 */
  public static BizException redisProtocolFailure() {
    return new RedisProtocolFailure();
  }

  /** 启动初始化保留原错误传播和兜底语义，不贡献业务熔断样本。 */
  public static void initialize(Runnable operation) {
    Boolean previous = INITIALIZING.get();
    INITIALIZING.set(true);
    try {
      operation.run();
    } finally {
      if (previous == null) INITIALIZING.remove();
      else INITIALIZING.set(previous);
    }
  }

  /** 后台已捕获的数据库故障计入当前整操作；不新开 gate，也不阻断后续证据写入。 */
  public static void noteMysqlFailure(Throwable failure) {
    Entry entry = MYSQL_ENTRY.get();
    if (entry == null
        || Boolean.TRUE.equals(MYSQL_FAILURE_RECORDED.get())
        || !dependencyFailure(true, failure)) return;
    Tracer.traceEntry(failure, entry);
    MYSQL_FAILURE_RECORDED.set(true);
    Metrics.counter("stock.dependency.failure", "resource", entry.getResourceWrapper().getName())
        .increment();
  }

  /** 仅连接/事务/协议错误计入异常；Redis 错误不得再次给 MySQL 计分。 */
  private static boolean dependencyFailure(boolean mysql, Throwable failure) {
    if (failure instanceof BizException business) {
      return !mysql && business instanceof RedisProtocolFailure;
    }
    if (mysql) {
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        String name = cause.getClass().getName();
        if (name.startsWith("org.springframework.data.redis.") || name.startsWith("io.lettuce."))
          return false;
      }
    }
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (!mysql
          && (cause instanceof RedisProtocolFailure
              || cause.getClass().getName().startsWith("org.springframework.data.redis.")
              || cause.getClass().getName().startsWith("io.lettuce."))) return true;
      if (cause instanceof DataAccessResourceFailureException
          || cause instanceof QueryTimeoutException
          || cause instanceof RecoverableDataAccessException
          || cause instanceof TransientDataAccessResourceException
          || cause instanceof PessimisticLockingFailureException) return true;
      if (mysql && cause instanceof TransactionException) return true;
      if (mysql && cause instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (sql instanceof SQLTimeoutException
            || (state != null
                && (state.startsWith("08") || state.startsWith("40") || state.startsWith("HYT"))))
          return true;
      }
    }
    return false;
  }

  /** 隐藏连接参数并保留 cause，调用方继续按原订单号/操作键处理 UNKNOWN。 */
  private static BizException unavailable(String resource, Throwable cause) {
    BizException failure =
        new BizException(
            resource.startsWith("stock-redis-")
                ? "REDIS_SERVICE_UNAVAILABLE"
                : "STOCK_SERVICE_UNAVAILABLE",
            "依赖服务暂时不可用，请稍后重试",
            503);
    failure.initCause(cause);
    return failure;
  }

  /** 可区分于明确业务拒绝的 Redis 结果未知异常。 */
  private static final class RedisProtocolFailure extends BizException {
    /** 创建内部协议故障，响应沿用 Redis 503 契约。 */
    private RedisProtocolFailure() {
      super("REDIS_SERVICE_UNAVAILABLE", "Redis 结果无法确认，请稍后重试", 503);
    }
  }
}
