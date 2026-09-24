package com.cloudmall.order.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在独立事务中持久化普通订单的库存预扣意图和补偿结果。 */
@Service
public class OrderReservationIntentService {
  /** 业务时间按 Asia/Shanghai 写入 DATETIME(3)。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** 预扣意图持久层。 */
  private final OrderReservationIntentMapper reservationIntentMapper;

  /** 创建预扣意图服务。 */
  public OrderReservationIntentService(OrderReservationIntentMapper reservationIntentMapper) {
    this.reservationIntentMapper = reservationIntentMapper;
  }

  /** 独立提交 INIT；已有用户幂等键时返回 false，不执行第二次预扣。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean createIntent(OrderReservationIntentPO intent) {
    // 1. 在远程库存调用之前提交带原订单号和明细快照的意图。
    return reservationIntentMapper.insertIfAbsent(intent) == 1;
  }

  /** 查询用户幂等键对应的持久意图。 */
  public List<OrderReservationIntentPO> findByUserAndKey(long userId, String idempotencyKey) {
    return reservationIntentMapper.findByUserAndKey(userId, idempotencyKey);
  }

  /** 独立确认远端库存已成功预扣。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markReserved(String orderNo) {
    // 1. 只允许 INIT 意图进入 RESERVED。
    int changed = reservationIntentMapper.updateStatus(orderNo, "INIT", "RESERVED", now());
    // 2. 缺失或重复状态均交由调用方和恢复任务处理。
    if (changed != 1) {
      throw new BizException(ErrorCodes.DUPLICATE, "库存预扣意图状态已变化", 409);
    }
  }

  /** 独立确认同步补偿已成功释放库存。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markReleased(String orderNo) {
    // 1. 仅释放尚未和订单提交绑定的意图。
    reservationIntentMapper.markReleased(orderNo, now());
  }

  /** 记录补偿失败，供恢复任务再次处理。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(String orderNo, String error) {
    // 1. 保留原订单号和明细，不覆盖 COMMITTED/RELEASED 终态。
    reservationIntentMapper.recordFailure(orderNo, error, now().plusSeconds(30));
  }

  /** 将未收到响应的预扣独立持久化为结果未知，等待原订单号查询。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markReserveUnknown(String orderNo, String error) {
    // 1. 只有尚未确认库存结果的 INIT 意图可进入未知状态。
    reservationIntentMapper.markReserveUnknown(orderNo, error, now().plusSeconds(30));
  }

  /** 返回当前 Asia/Shanghai 本地时间。 */
  private static LocalDateTime now() {
    return LocalDateTime.now(BUSINESS_ZONE);
  }
}
