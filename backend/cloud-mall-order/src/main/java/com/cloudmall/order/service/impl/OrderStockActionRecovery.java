package com.cloudmall.order.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.po.OrderStockActionPO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
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
  /** 当前恢复器实例租约标识。 */
  private final String owner = UUID.randomUUID().toString();
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
      if (actionMapper.claim(action.id, owner, claimedAt) != 1) continue;
      recoverOne(action);
    }
  }

  /** 查询事实、按原操作键重试、确认最终状态。 */
  private void recoverOne(OrderStockActionPO action) {
    try {
      // 1. 先查询库存事实；已到达目标终态直接完成，无需重新写入。
      String target = "CONFIRM".equals(action.actionType) ? "CONFIRMED" : "ROLLED_BACK";
      String opposite = "CONFIRM".equals(action.actionType) ? "ROLLED_BACK" : "CONFIRMED";
      String current = stockStatus(action.orderNo);
      if (target.equals(current)) {
        actionMapper.finish(action.id, owner, "SUCCEEDED", null, now());
        return;
      }
      if (opposite.equals(current) || "REJECTED".equals(current)) {
        actionMapper.finish(action.id, owner, "CONFLICT",
            "库存已处于不可逆的相反终态: " + current, now());
        log.error("订单库存动作与库存事实冲突，需人工对账，orderNo={}，action={}，stock={}",
            action.orderNo, action.actionType, current);
        return;
      }
      if (!"RESERVED".equals(current))
        throw new IllegalStateException("库存预扣尚未达到可结算状态: " + current);
      // 2. 只对 RESERVED 使用同一操作键重试，远端写响应不能代表最终事实。
      StockClient.Action request = new StockClient.Action(action.operationKey);
      ApiResponse<?> result = "CONFIRM".equals(action.actionType)
          ? stockClient.confirm(action.orderNo, request)
          : stockClient.rollback(action.orderNo, request);
      if (result == null || !"0".equals(result.code))
        throw new IllegalStateException("库存动作响应未确认");
      // 3. 再读持久状态；仅目标终态允许把本地动作标记成功。
      String afterWrite = stockStatus(action.orderNo);
      if (target.equals(afterWrite)) {
        actionMapper.finish(action.id, owner, "SUCCEEDED", null, now());
      } else if (opposite.equals(afterWrite)) {
        actionMapper.finish(action.id, owner, "CONFLICT",
            "库存写入后为相反终态: " + afterWrite, now());
        log.error("订单库存动作写入后发生终态冲突，orderNo={}，action={}",
            action.orderNo, action.actionType);
      } else {
        throw new IllegalStateException("库存动作写入后的状态待确认: " + afterWrite);
      }
    } catch (RuntimeException unknownResult) {
      // 4. 查询或写入失败只记录未知，不猜测失败或执行相反操作。
      long delaySeconds = Math.min(60, 5L * Math.max(1, action.attempts == null ? 1 : action.attempts));
      actionMapper.retry(action.id, owner, unknownResult.toString(), now().plusSeconds(delaySeconds), now());
      log.warn("订单库存动作结果待恢复，orderNo={}，action={}",
          action.orderNo, action.actionType, unknownResult);
    }
  }

  /** 查询库存端唯一的预扣最终状态。 */
  private String stockStatus(String orderNo) {
    ApiResponse<StockReservationStatusVO> response = stockClient.reservation(orderNo);
    if (response == null || !"0".equals(response.code) || response.data == null)
      throw new IllegalStateException("库存状态查询结果未知");
    return response.data.status;
  }

  /** 获取与 MySQL DATETIME(3) 一致的本地时间。 */
  private static LocalDateTime now() {
    return LocalDateTime.now(BUSINESS_ZONE);
  }
}
