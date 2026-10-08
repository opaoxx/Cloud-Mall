package com.cloudmall.order.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.po.OrderStockActionPO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  /** 定期处理订单事务已提交但库存动作尚未确认的记录。 */
  @Scheduled(fixedDelayString = "${cloudmall.order.stock-action-recovery-interval-ms:5000}")
  public void recoverDue() {
    // 1. 扫描到期的非终态动作。
    List<OrderStockActionPO> due = actionMapper.findDue(now(), 50);
    // 2. 逐条认领，避免多实例重复执行相反动作。
    for (OrderStockActionPO action : due) {
      LocalDateTime claimedAt = now();
      // 每次认领使用独立代际标识，避免同实例重认领后接受迟到结果。
      String claimOwner = UUID.randomUUID().toString();
      if (actionMapper.claim(action.id, claimOwner, claimedAt) != 1) continue;
      recoverOne(action, claimOwner);
    }
  }

  /** 查询事实并携带本次认领标识完成本地CAS；失效零更新不猜测结果或再次写入。 */
  private void recoverOne(OrderStockActionPO action, String claimOwner) {
    try {
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
      if (target.equals(current)) {
        actionMapper.finish(action.id, claimOwner, "SUCCEEDED", null, now());
        return;
      }
      if (opposite.equals(current) || "REJECTED".equals(current)) {
        actionMapper.finish(action.id, claimOwner, "CONFLICT", "库存已处于不可逆的相反终态: " + current, now());
        log.error("订单库存动作与库存事实冲突，需人工对账，orderNo={}，action={}，stock={}",
            action.orderNo, action.actionType, current);
        return;
      }
      if (!"RESERVED".equals(current))
        throw new IllegalStateException("库存预扣尚未达到可结算状态: " + current);
      // 2. 只对 RESERVED 使用同一操作键重试，远端写响应不能代表最终事实。
      StockClient.Action request = new StockClient.Action(action.operationKey);
      ApiResponse<?> result = "CONFIRM".equals(action.actionType)
          ? OrderStockCircuitBreaker.call(OrderStockCircuitBreaker.ACTION,
              () -> stockClient.confirm(action.orderNo, request))
          : OrderStockCircuitBreaker.call(OrderStockCircuitBreaker.ACTION,
              () -> stockClient.rollback(action.orderNo, request));
      if (result == null || !"0".equals(result.code))
        throw new IllegalStateException("库存动作响应未确认");
      // 3. 再读持久状态；仅目标终态允许把本地动作标记成功。
      String afterWrite = stockStatus(action.orderNo);
      if (target.equals(afterWrite)) {
        actionMapper.finish(action.id, claimOwner, "SUCCEEDED", null, now());
      } else if (opposite.equals(afterWrite)) {
        actionMapper.finish(action.id, claimOwner, "CONFLICT", "库存写入后为相反终态: " + afterWrite, now());
        log.error("订单库存动作写入后发生终态冲突，orderNo={}，action={}",
            action.orderNo, action.actionType);
      } else {
        throw new IllegalStateException("库存动作写入后的状态待确认: " + afterWrite);
      }
    } catch (RuntimeException unknownResult) {
      // 4. 查询或写入失败只记录未知，不猜测失败或执行相反操作。
      long delaySeconds = Math.min(60, 5L * Math.max(1, action.attempts == null ? 1 : action.attempts));
      actionMapper.retry(
          action.id, claimOwner, unknownResult.toString(), now().plusSeconds(delaySeconds), now());
      log.warn("订单库存动作结果待恢复，orderNo={}，action={}",
          action.orderNo, action.actionType, unknownResult);
    }
  }

  /** 核验响应原订单号再读取库存状态，错单或缺失身份沿原UNKNOWN退避处理。 */
  private String stockStatus(String orderNo) {
    ApiResponse<StockReservationStatusVO> response =
        OrderStockCircuitBreaker.call(
            OrderStockCircuitBreaker.STATUS, () -> stockClient.reservation(orderNo));
    if (response == null || !"0".equals(response.code) || response.data == null)
      throw new IllegalStateException("库存状态查询结果未知");
    if (!Objects.equals(orderNo, response.data.orderNo))
      throw new IllegalStateException("库存状态查询订单号与原动作不一致");
    return response.data.status;
  }

  /** 获取与 MySQL DATETIME(3) 一致的本地时间。 */
  private static LocalDateTime now() {
    return LocalDateTime.now(BUSINESS_ZONE);
  }
}
