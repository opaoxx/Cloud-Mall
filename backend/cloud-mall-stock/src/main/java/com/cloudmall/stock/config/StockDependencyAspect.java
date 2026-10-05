package com.cloudmall.stock.config;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.util.StockDependencyGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 必须位于 Spring transaction advisor 外，覆盖实际 begin 和最终 commit/rollback。 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class StockDependencyAspect {
  /** 后台依赖阻断与重试日志，不把未执行的批次报告为成功。 */
  private static final Logger log = LoggerFactory.getLogger(StockDependencyAspect.class);

  /** 在库存完整事务外开启一次保护，排除独立 Seata 演练。 */
  @Around(
      "execution(public * com.cloudmall.stock..*(..))"
          + " && @annotation(org.springframework.transaction.annotation.Transactional)"
          + " && !within(com.cloudmall.stock.service.impl.Cm18*)")
  public Object transaction(ProceedingJoinPoint invocation) throws Throwable {
    return StockDependencyGuard.execute(
        StockDependencyGuard.MYSQL_TRANSACTION, invocation::proceed);
  }

  /** 数据库状态查询保持只读；普通库存缓存命中不在此处阻断。 */
  @Around(
      "execution(public * com.cloudmall.stock.service.impl.StockServiceImpl+.reservation(..)) ||"
          + " execution(public *"
          + " com.cloudmall.stock.service.SeckillReservationQueryService.find(..))")
  public Object read(ProceedingJoinPoint invocation) throws Throwable {
    return StockDependencyGuard.execute(StockDependencyGuard.MYSQL_READ, invocation::proceed);
  }

  /** 在调度整批恢复之前保护数据库边界，内部嵌套事务沿用本批 Entry。 */
  @Around(
      "execution(public * com.cloudmall.stock.service.impl.StockCacheReconciler.repairPending(..))"
          + " || execution(public *"
          + " com.cloudmall.stock.service.impl.StockCacheReconciler.retryIncompleteCalibration(..))"
          + " || execution(public *"
          + " com.cloudmall.stock.service.impl.StockCacheReconciler.unblockResolvedLegacyStocks(..))"
          + " || execution(public *"
          + " com.cloudmall.stock.service.impl.StockConsistencyReconciler.scheduledAudit(..)) ||"
          + " execution(public *"
          + " com.cloudmall.stock.service.impl.SeckillQuotaSettlementRecovery.recoverDue(..)) ||"
          + " execution(public *"
          + " com.cloudmall.stock.service.impl.SeckillQuotaSettlementRecovery.scheduledRecover(..))"
          + " || execution(public *"
          + " com.cloudmall.stock.service.impl.SeckillOrderOutboxDispatcher.dispatch(..))")
  public Object recovery(ProceedingJoinPoint invocation) throws Throwable {
    try {
      return StockDependencyGuard.execute(StockDependencyGuard.MYSQL_RECOVERY, invocation::proceed);
    } catch (BizException unavailable) {
      if (unavailable.getHttpStatus() != 503) throw unavailable;
      log.warn(
          "库存恢复批次依赖不可用，保留原意图与租约等待重试，operation={}，code={}",
          invocation.getSignature().getName(),
          unavailable.getCode());
      return ((MethodSignature) invocation.getSignature()).getReturnType() == int.class ? 0 : null;
    }
  }
}
