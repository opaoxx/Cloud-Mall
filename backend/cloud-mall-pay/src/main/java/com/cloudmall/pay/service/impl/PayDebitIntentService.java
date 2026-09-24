package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 远程扣款前独立提交不可变支付意图，覆盖进程退出窗口。 */
@Service
public class PayDebitIntentService {
  /** 项目数据库 DATETIME(3) 的业务时区。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** 支付意图持久层。 */
  private final PayDebitIntentMapper payDebitIntentMapper;

  /** 创建支付扣款意图服务。 */
  public PayDebitIntentService(PayDebitIntentMapper payDebitIntentMapper) {
    this.payDebitIntentMapper = payDebitIntentMapper;
  }

  /** 独立提交 INIT；相同 payNo 的不同订单或金额必须冲突。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayDebitIntentPO ensureIntent(PayVO payment) {
    // 1. 在调用用户服务之前先保存可恢复的扣款意图。
    LocalDateTime currentTime = LocalDateTime.now(BUSINESS_ZONE);
    PayDebitIntentPO intent = new PayDebitIntentPO();
    intent.id = Math.abs(UUID.randomUUID().getMostSignificantBits());
    intent.payNo = payment.payNo;
    intent.orderNo = payment.orderNo;
    intent.userId = payment.userId;
    intent.amount = payment.amount;
    intent.status = "INIT";
    intent.attempts = 0;
    intent.nextRetryAt = currentTime;
    intent.createdAt = currentTime;
    intent.updatedAt = currentTime;
    payDebitIntentMapper.insertIfAbsent(intent);
    // 2. 唯一键冲突时检查请求与原意图完全一致，不能重用支付键。
    PayDebitIntentPO durableIntent = payDebitIntentMapper.findByPayNo(payment.payNo);
    if (durableIntent == null
        || !Objects.equals(durableIntent.orderNo, payment.orderNo)
        || !Objects.equals(durableIntent.userId, payment.userId)
        || durableIntent.amount.compareTo(payment.amount) != 0) {
      throw new BizException(ErrorCodes.DUPLICATE, "支付操作键对应的请求不一致", 409);
    }
    return durableIntent;
  }
}
