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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
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

  /** 扫描陈旧意图，保留中断并停止后续操作；普通扫描失败传播、单条数据库故障隔离。 */
  @Scheduled(fixedDelayString = "${cloudmall.order.reservation-recovery-interval-ms:30000}")
  public void recoverStaleReservations() {
    if (Thread.currentThread().isInterrupted()) return;
    // 1. 留出正常远程调用与本地事务提交时间，避免抢先处理进行中的请求。
    LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
    LocalDateTime staleBefore = now.minusMinutes(2);
    List<OrderReservationIntentPO> recoverable;
    try {
      if (Thread.currentThread().isInterrupted()) return;
      recoverable = reservationIntentMapper.findRecoverable(staleBefore, now, 50);
    } catch (RuntimeException scanFailure) {
      if (interrupted(scanFailure)) return;
      throw scanFailure;
    }
    if (Thread.currentThread().isInterrupted()) return;
    // 2. 为每条意图领取一分钟的恢复租约，多实例可安全重复扫描。
    for (OrderReservationIntentPO intent : recoverable) {
      if (Thread.currentThread().isInterrupted()) return;
      try {
        int claimed =
            reservationIntentMapper.claimRecovery(intent.orderNo, now, now.plusMinutes(1));
        if (Thread.currentThread().isInterrupted()) return;
        if (claimed != 1) continue;
        recoverOne(intent);
      } catch (DataAccessException databaseFailure) {
        if (interrupted(databaseFailure)) return;
        log.warn("普通预扣恢复单条数据库操作失败，本次跳过，orderNo={}", intent.orderNo, databaseFailure);
      } catch (RuntimeException candidateFailure) {
        if (interrupted(candidateFailure)) return;
        throw candidateFailure;
      }
    }
  }

  /** 对一条未完成意图重新确认库存结果并按原订单号回滚。 */
  private void recoverOne(OrderReservationIntentPO intent) {
    try {
      if (Thread.currentThread().isInterrupted()) return;
      // 1. 若订单已提交，只校正意图状态，避免释放有效订单的库存。
      String tableName = tableFromOrderNo(intent.orderNo);
      boolean committed = orderSqlMapper.orderExists(tableName, intent.orderNo);
      if (Thread.currentThread().isInterrupted()) return;
      if (committed) {
        reservationIntentMapper.forceCommitted(intent.orderNo, LocalDateTime.now(BUSINESS_ZONE));
        return;
      }
      // 2. Feign 写请求的未知结果只用原订单号查询，不重新生成或盲目重发预扣。
      String status = reservationStatus(intent.orderNo);
      if (Thread.currentThread().isInterrupted()) return;
      if ("NOT_FOUND".equals(status) || "INIT".equals(status)) {
        // 3. 未观察到最终事实时，仅重试原订单号和原快照；不可直接假定未执行。
        List<StockClient.Line> originalLines =
            objectMapper.readValue(
                intent.itemsSnapshot, new TypeReference<List<StockClient.Line>>() {});
        if (Thread.currentThread().isInterrupted()) return;
        if (originalLines.isEmpty()) throw new IllegalStateException("预扣意图明细为空");
        try {
          if (Thread.currentThread().isInterrupted()) return;
          OrderStockCircuitBreaker.call(
              OrderStockCircuitBreaker.RESERVE,
              () ->
                  stockClient.reserve(
                      new StockClient.Reservation(intent.orderNo, originalLines, "NORMAL")));
        } catch (FeignException explicitRejection) {
          if (interrupted(explicitRejection)) return;
          if (explicitRejection.status() != 409) throw explicitRejection;
        }
        if (Thread.currentThread().isInterrupted()) return;
        status = reservationStatus(intent.orderNo);
        if (Thread.currentThread().isInterrupted()) return;
      }
      if ("RESERVED".equals(status) || "ALREADY_RESERVED".equals(status)) {
        if (Thread.currentThread().isInterrupted()) return;
        // 4. 已确认预扣才调用幂等回滚，随后结束意图。
        ApiResponse<?> rollbackResult =
            OrderStockCircuitBreaker.call(
                OrderStockCircuitBreaker.ACTION, () -> stockClient.rollback(intent.orderNo));
        if (Thread.currentThread().isInterrupted()) return;
        if (rollbackResult == null || !"0".equals(rollbackResult.code))
          throw new IllegalStateException("库存回滚结果未确认");
      } else if (!"REJECTED".equals(status) && !"ROLLED_BACK".equals(status)) {
        throw new IllegalStateException("库存预扣状态需要人工核对: " + status);
      }
      if (Thread.currentThread().isInterrupted()) return;
      reservationIntentMapper.markReleased(intent.orderNo, LocalDateTime.now(BUSINESS_ZONE));
    } catch (DataAccessException databaseFailure) {
      // 数据库写结果未知时不追加失败写或重复RPC，由候选边界保留原事实。
      throw databaseFailure;
    } catch (Exception recoveryFailure) {
      if (interrupted(recoveryFailure)) return;
      // 先保存原失败日志，错误记录写本身失败也不得遮蔽原业务原因。
      log.warn("普通订单预扣恢复失败，保留原意图待重试，orderNo={}", intent.orderNo, recoveryFailure);
      LocalDateTime retryAt = LocalDateTime.now(BUSINESS_ZONE).plusMinutes(1);
      if (Thread.currentThread().isInterrupted()) return;
      reservationIntentMapper.recordFailure(intent.orderNo, recoveryFailure.toString(), retryAt);
    }
  }

  /**
   * 仅从当前标志或明确InterruptedException识别中断并保留信号。
   * cause按对象身份防循环，不将SocketTimeout/一般InterruptedIOException误判成取消。
   */
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

  /** 核验响应身份并读取库存事实；缺失或错单抛异常，交由原失败退避路径处理。 */
  private String reservationStatus(String orderNo) throws InterruptedException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("恢复线程已中断");
    ApiResponse<StockReservationStatusVO> response =
        OrderStockCircuitBreaker.call(
            OrderStockCircuitBreaker.STATUS, () -> stockClient.reservation(orderNo));
    if (response == null || !"0".equals(response.code) || response.data == null)
      throw new IllegalStateException("库存预扣查询结果未知");
    if (!Objects.equals(orderNo, response.data.orderNo))
      throw new IllegalStateException("库存预扣查询订单号与原意图不一致");
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
