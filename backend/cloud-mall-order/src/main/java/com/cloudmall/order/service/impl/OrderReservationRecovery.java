package com.cloudmall.order.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 依据已提交的预扣意图恢复库存调用结果未知或本地订单回滚的请求。 */
@Component
public class OrderReservationRecovery {
  /** 记录无法自动完成的补偿，方便本地排查。 */
  private static final Logger log = LoggerFactory.getLogger(OrderReservationRecovery.class);

  /** 按 Asia/Shanghai 写入数据库 DATETIME(3)。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** 订单号的月份前缀格式。 */
  private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

  /** 库存预扣及回滚的内部 Feign 客户端。 */
  private final StockClient stockClient;

  /** 普通预扣意图持久层。 */
  private final OrderReservationIntentMapper reservationIntentMapper;

  /** 订单事实查询持久层。 */
  private final OrderSqlMapper orderSqlMapper;

  /** 读取已持久化的原始 SKU/数量快照。 */
  private final ObjectMapper objectMapper;

  /** 创建普通订单预扣恢复任务。 */
  public OrderReservationRecovery(
      StockClient stockClient,
      OrderReservationIntentMapper reservationIntentMapper,
      OrderSqlMapper orderSqlMapper,
      ObjectMapper objectMapper) {
    this.stockClient = stockClient;
    this.reservationIntentMapper = reservationIntentMapper;
    this.orderSqlMapper = orderSqlMapper;
    this.objectMapper = objectMapper;
  }

  /** 扫描已超时但尚未和订单提交绑定的预扣意图。 */
  @Scheduled(fixedDelayString = "${cloudmall.order.reservation-recovery-interval-ms:30000}")
  public void recoverStaleReservations() {
    // 1. 留出正常远程调用与本地事务提交时间，避免抢先处理进行中的请求。
    LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
    LocalDateTime staleBefore = now.minusMinutes(2);
    List<OrderReservationIntentPO> recoverable =
        reservationIntentMapper.findRecoverable(staleBefore, now, 50);
    // 2. 为每条意图领取一分钟的恢复租约，多实例可安全重复扫描。
    for (OrderReservationIntentPO intent : recoverable) {
      if (reservationIntentMapper.claimRecovery(intent.orderNo, now, now.plusMinutes(1)) != 1)
        continue;
      recoverOne(intent);
    }
  }

  /** 对一条未完成意图重新确认库存结果并按原订单号回滚。 */
  private void recoverOne(OrderReservationIntentPO intent) {
    try {
      // 1. 若订单已提交，只校正意图状态，避免释放有效订单的库存。
      String tableName = tableFromOrderNo(intent.orderNo);
      if (orderSqlMapper.orderExists(tableName, intent.orderNo)) {
        reservationIntentMapper.forceCommitted(intent.orderNo, LocalDateTime.now(BUSINESS_ZONE));
        return;
      }
      // 2. Feign 写请求的未知结果只用原订单号查询，不重新生成或盲目重发预扣。
      String status = reservationStatus(intent.orderNo);
      if ("NOT_FOUND".equals(status) || "INIT".equals(status)) {
        // 3. 未观察到最终事实时，仅重试原订单号和原快照；不可直接假定未执行。
        List<StockClient.Line> originalLines =
            objectMapper.readValue(
                intent.itemsSnapshot, new TypeReference<List<StockClient.Line>>() {});
        if (originalLines.isEmpty()) throw new IllegalStateException("预扣意图明细为空");
        try {
          stockClient.reserve(
              new StockClient.Reservation(intent.orderNo, originalLines, "NORMAL"));
        } catch (FeignException explicitRejection) {
          if (explicitRejection.status() != 409) throw explicitRejection;
        }
        status = reservationStatus(intent.orderNo);
      }
      if ("RESERVED".equals(status) || "ALREADY_RESERVED".equals(status)) {
        // 4. 已确认预扣才调用幂等回滚，随后结束意图。
        ApiResponse<?> rollbackResult = stockClient.rollback(intent.orderNo);
        if (rollbackResult == null || !"0".equals(rollbackResult.code))
          throw new IllegalStateException("库存回滚结果未确认");
      } else if (!"REJECTED".equals(status)
          && !"ROLLED_BACK".equals(status)) {
        throw new IllegalStateException("库存预扣状态需要人工核对: " + status);
      }
      reservationIntentMapper.markReleased(intent.orderNo, LocalDateTime.now(BUSINESS_ZONE));
    } catch (Exception recoveryFailure) {
      LocalDateTime retryAt = LocalDateTime.now(BUSINESS_ZONE).plusMinutes(1);
      reservationIntentMapper.recordFailure(
          intent.orderNo, recoveryFailure.toString(), retryAt);
      log.warn("普通订单预扣恢复失败，稍后重试，orderNo={}", intent.orderNo, recoveryFailure);
    }
  }

  /** 按原订单号读取库存端的当前或终态事实。 */
  private String reservationStatus(String orderNo) {
    ApiResponse<StockReservationStatusVO> response = stockClient.reservation(orderNo);
    if (response == null || !"0".equals(response.code) || response.data == null)
      throw new IllegalStateException("库存预扣查询结果未知");
    return response.data.status;
  }

  /** 从受控订单号生成物理月表名，避免把外部输入直接拼入 SQL。 */
  private static String tableFromOrderNo(String orderNo) {
    if (orderNo == null || !orderNo.matches("\\d{6}[A-Za-z0-9]+"))
      throw new IllegalArgumentException("预扣意图订单号格式无效");
    YearMonth.parse(orderNo.substring(0, 6), MONTH_FORMAT);
    return "mall_order_" + orderNo.substring(0, 6);
  }
}
