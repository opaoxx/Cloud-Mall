package com.cloudmall.pay.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 跨用户余额和订单服务的可恢复支付扣款意图。 */
@TableName("pay_debit_intent")
public class PayDebitIntentPO {
  /** 意图内部主键。 */
  @TableId public Long id;

  /** 全局唯一的支付操作键。 */
  @TableField("pay_no")
  public String payNo;

  /** 关联订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 支付用户主键。 */
  @TableField("user_id")
  public Long userId;

  /** 与订单应付金额一致的正数金额。 */
  public BigDecimal amount;

  /** INIT、DEBITED、ORDER_SYNC_UNKNOWN、SUCCESS、REFUND_PENDING、REFUNDED 或 FAILED。 */
  public String status;

  /** 从原支付单复制的不可变扣款协议，恢复时不得读取当前开关重新选择。 */
  @TableField("debit_mode")
  public String debitMode;

  /** 累计领取的恢复尝试次数，非负且达到INT上限后饱和。 */
  public Integer attempts;

  /** 下次允许恢复的时间。 */
  @TableField("next_retry_at")
  public LocalDateTime nextRetryAt;

  /** 当前恢复任务实例标识。 */
  @TableField("lease_owner")
  public String leaseOwner;

  /** 租约失效时间。 */
  @TableField("lease_until")
  public LocalDateTime leaseUntil;

  /** 最近一次远程调用或结果核对失败原因。 */
  @TableField("last_error")
  public String lastError;

  /** 意图创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 意图更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;

  /** 创建空的支付扣款意图对象。 */
  public PayDebitIntentPO() {}
}
