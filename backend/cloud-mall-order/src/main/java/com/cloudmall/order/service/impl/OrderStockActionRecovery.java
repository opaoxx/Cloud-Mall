package com.cloudmall.order.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.po.OrderStockActionPO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 根据库存持久事实完成订单提交后的确认或回滚动作。 */
@Component
public class OrderStockActionRecovery {
  /** 无法自动收敛的库存动作日志。 */
  private static final Logger log = LoggerFactory.getLogger(OrderStockActionRecovery.class);
  /** 业务数据库时区。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** 订单库存动作持久层。 */
  private final OrderStockActionMapper actionMapper;

  /** 库存状态查询与幂等动作客户端。 */
  private final StockClient stockClient;

  /** 创建库存动作恢复器。 */
  public OrderStockActionRecovery(OrderStockActionMapper actionMapper, StockClient stockClient) {
    this.actionMapper = actionMapper;
    this.stockClient = stockClient;
  }

  /** 定期恢复库存动作；保留中断并停止，普通扫描失败传播、单条数据库故障隔离。 */
  @Scheduled(fixedDelayString = "${cloudmall.order.stock-action-recovery-interval-ms:5000}")
  public void recoverDue() {
    if (Thread.currentThread().isInterrupted()) return;
    // 1. 扫描到期的非终态动作，扫描失败仍传播，中断优先停止。
    List<OrderStockActionPO> due;
    try {
      if (Thread.currentThread().isInterrupted()) return;
      due = actionMapper.findDue(now(), 50);
    } catch (RuntimeException scanFailure) {
      if (interrupted(scanFailure)) return;
      throw scanFailure;
    }
    if (Thread.currentThread().isInterrupted()) return;
    // 2. 逐条认领；中断后不再操作当前或后续候选。
    for (OrderStockActionPO action : due) {
      if (Thread.currentThread().isInterrupted()) return;
      try {
        LocalDateTime claimedAt = now();
        // 每次认领使用独立代际标识，避免同实例重认领后接受迟到结果。
        String claimOwner = UUID.randomUUID().toString();
        if (Thread.currentThread().isInterrupted()) return;
        int claimed = actionMapper.claim(action.id, claimOwner, claimedAt);
        if (Thread.currentThread().isInterrupted()) return;
        if (claimed != 1) continue;
        recoverOne(action, claimOwner);
        if (Thread.currentThread().isInterrupted()) return;
      } catch (DataAccessException databaseFailure) {
        if (interrupted(databaseFailure)) return;
        // 保留原持久事实与已提交租约，不猜提交结果或追加当前动作写入。
        log.warn(
            "订单库存动作单条数据库操作失败，本次跳过，orderNo={}，action={}",
            action.orderNo,
            action.actionType,
            databaseFailure);
      } catch (RuntimeException candidateFailure) {
        if (interrupted(candidateFailure)) return;
        throw candidateFailure;
      }
    }
  }

  /** 查询事实并携带本次认领标识完成本地CAS；失效零更新不猜测结果或再次写入。 */
  private void recoverOne(OrderStockActionPO action, String claimOwner) {
    try {
      if (Thread.currentThread().isInterrupted()) return;
      // 未知类型不得默认成回滚；原事实保留并走既有UNKNOWN退避。
      if (!"CONFIRM".equals(action.actionType) && !"ROLLBACK".equals(action.actionType)) {
        throw new IllegalStateException("库存动作类型不受支持: " + action.actionType);
      }
      // 即使库存已到目标终态，也必须先核验原订单/方向与持久操作键绑定。
      if (action.orderNo == null
          || action.orderNo.isBlank()
          || !Objects.equals(action.operationKey, action.orderNo + ":" + action.actionType)) {
        throw new IllegalStateException("库存动作操作键与原订单或类型不匹配");
      }
      // 1. 先查询库存事实；已到达目标终态直接完成，无需重新写入。
      String target = "CONFIRM".equals(action.actionType) ? "CONFIRMED" : "ROLLED_BACK";
      String opposite = "CONFIRM".equals(action.actionType) ? "ROLLED_BACK" : "CONFIRMED";
      String current = stockStatus(action.orderNo);
      if (Thread.currentThread().isInterrupted()) return;
      if (target.equals(current)) {
        if (Thread.currentThread().isInterrupted()) return;
        actionMapper.finish(action.id, claimOwner, "SUCCEEDED", null, now());
        if (Thread.currentThread().isInterrupted()) return;
        return;
      }
      if (opposite.equals(current) || "REJECTED".equals(current)) {
        if (Thread.currentThread().isInterrupted()) return;
        actionMapper.finish(action.id, claimOwner, "CONFLICT", "库存已处于不可逆的相反终态: " + current, now());
        if (Thread.currentThread().isInterrupted()) return;
        log.error("订单库存动作与库存事实冲突，需人工对账，orderNo={}，action={}，stock={}",
            action.orderNo, action.actionType, current);
        return;
      }
      if (!"RESERVED".equals(current))
        throw new IllegalStateException("库存预扣尚未达到可结算状态: " + current);
      // 2. 只对 RESERVED 使用同一操作键重试，远端写响应不能代表最终事实。
      StockClient.Action request = new StockClient.Action(action.operationKey);
      if (Thread.currentThread().isInterrupted()) return;
      ApiResponse<?> result = "CONFIRM".equals(action.actionType)
          ? OrderStockCircuitBreaker.call(OrderStockCircuitBreaker.ACTION,
              () -> stockClient.confirm(action.orderNo, request))
          : OrderStockCircuitBreaker.call(OrderStockCircuitBreaker.ACTION,
              () -> stockClient.rollback(action.orderNo, request));
      if (Thread.currentThread().isInterrupted()) return;
      if (result == null || !"0".equals(result.code))
        throw new IllegalStateException("库存动作响应未确认");
      // 3. 再读持久状态；仅目标终态允许把本地动作标记成功。
      String afterWrite = stockStatus(action.orderNo);
      if (Thread.currentThread().isInterrupted()) return;
      if (target.equals(afterWrite)) {
        if (Thread.currentThread().isInterrupted()) return;
        actionMapper.finish(action.id, claimOwner, "SUCCEEDED", null, now());
        if (Thread.currentThread().isInterrupted()) return;
      } else if (opposite.equals(afterWrite)) {
        if (Thread.currentThread().isInterrupted()) return;
        actionMapper.finish(action.id, claimOwner, "CONFLICT", "库存写入后为相反终态: " + afterWrite, now());
        if (Thread.currentThread().isInterrupted()) return;
        log.error("订单库存动作写入后发生终态冲突，orderNo={}，action={}",
            action.orderNo, action.actionType);
      } else {
        throw new IllegalStateException("库存动作写入后的状态待确认: " + afterWrite);
      }
    } catch (InterruptedException interruptedCall) {
      Thread.currentThread().interrupt();
    } catch (DataAccessException databaseFailure) {
      if (interrupted(databaseFailure)) return;
      // 完成写数据库失败不是远端UNKNOWN，不再尝试retry或重新执行Stock动作。
      throw databaseFailure;
    } catch (RuntimeException unknownResult) {
      if (interrupted(unknownResult)) return;
      // 4. 先保留原业务错误证据，再按原token尝试一次UNKNOWN退避。
      log.warn(
          "订单库存动作结果待恢复，orderNo={}，action={}", action.orderNo, action.actionType, unknownResult);
      long delaySeconds = Math.min(60, 5L * Math.max(1, action.attempts == null ? 1 : action.attempts));
      if (Thread.currentThread().isInterrupted()) return;
      actionMapper.retry(
          action.id, claimOwner, unknownResult.toString(), now().plusSeconds(delaySeconds), now());
      if (Thread.currentThread().isInterrupted()) return;
    }
  }

  /** GET前后保留中断；未中断时沿原身份门读取库存状态，错单仍走UNKNOWN退避。 */
  private String stockStatus(String orderNo) throws InterruptedException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("库存动作恢复线程已中断");
    ApiResponse<StockReservationStatusVO> response =
        OrderStockCircuitBreaker.call(
            OrderStockCircuitBreaker.STATUS, () -> stockClient.reservation(orderNo));
    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("库存动作恢复线程已中断");
    if (response == null || !"0".equals(response.code) || response.data == null)
      throw new IllegalStateException("库存状态查询结果未知");
    if (!Objects.equals(orderNo, response.data.orderNo))
      throw new IllegalStateException("库存状态查询订单号与原动作不一致");
    return response.data.status;
  }

  /** 按对象身份安全遍历cause，只认当前标志或明确InterruptedException，不猜普通IO超时。 */
  private static boolean interrupted(Throwable failure) {
    if (Thread.currentThread().isInterrupted()) return true;
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
      if (cause instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        return true;
      }
    }
    return false;
  }

  /** 获取与 MySQL DATETIME(3) 一致的本地时间。 */
  private static LocalDateTime now() {
    return LocalDateTime.now(BUSINESS_ZONE);
  }
}
