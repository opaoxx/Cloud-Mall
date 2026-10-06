package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.util.PayDebitModes;
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

  /** 支付创建时固定的模式和载荷来源。 */
  private final PayRecordMapper payRecordMapper;

  /** 创建支付扣款意图服务。 */
  public PayDebitIntentService(
      PayDebitIntentMapper payDebitIntentMapper, PayRecordMapper payRecordMapper) {
    this.payDebitIntentMapper = payDebitIntentMapper;
    this.payRecordMapper = payRecordMapper;
  }

  /** 独立提交 INIT；相同 payNo 的不同订单或金额必须冲突。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayDebitIntentPO ensureIntent(PayVO payment) {
    // 1. 模式只从原支付单复制，不使用当前部署开关改写旧支付协议。
    java.util.List<PayRecordPO> records = payRecordMapper.selectByPayNo(payment.payNo);
    if (records.size() != 1) throw PayDebitModes.unavailable();
    PayRecordPO record = records.get(0);
    PayDebitModes.requireSupported(record.debitMode);
    if (!Objects.equals(record.orderNo, payment.orderNo)
        || !Objects.equals(record.userId, payment.userId)
        || record.amount == null
        || record.amount.compareTo(payment.amount) != 0)
      throw new BizException(ErrorCodes.DUPLICATE, "支付单与扣款意图载荷不一致", 409);
    PayDebitIntentPO existing = payDebitIntentMapper.findByPayNo(payment.payNo);
    if (existing != null) PayDebitModes.requireSupported(existing.debitMode);
    // 2. 在调用用户服务之前先保存绑定原模式的可恢复扣款意图。
    LocalDateTime currentTime = LocalDateTime.now(BUSINESS_ZONE);
    PayDebitIntentPO intent = new PayDebitIntentPO();
    intent.id = Math.abs(UUID.randomUUID().getMostSignificantBits());
    intent.payNo = payment.payNo;
    intent.orderNo = payment.orderNo;
    intent.userId = payment.userId;
    intent.amount = payment.amount;
    intent.status = "INIT";
    intent.debitMode = PayDebitModes.persisted(record.debitMode);
    intent.attempts = 0;
    intent.nextRetryAt = currentTime;
    intent.createdAt = currentTime;
    intent.updatedAt = currentTime;
    payDebitIntentMapper.insertIfAbsent(intent);
    // 3. 唯一键冲突时核验原模式与载荷，不能重用支付键或改写路由。
    PayDebitIntentPO durableIntent = payDebitIntentMapper.findByPayNo(payment.payNo);
    if (durableIntent != null) PayDebitModes.requireSupported(durableIntent.debitMode);
    if (durableIntent == null
        || !Objects.equals(durableIntent.orderNo, payment.orderNo)
        || !Objects.equals(durableIntent.userId, payment.userId)
        || durableIntent.amount.compareTo(payment.amount) != 0
        || !Objects.equals(PayDebitModes.persisted(durableIntent.debitMode), intent.debitMode)) {
      throw new BizException(ErrorCodes.DUPLICATE, "支付操作键对应的请求不一致", 409);
    }
    return durableIntent;
  }
}
