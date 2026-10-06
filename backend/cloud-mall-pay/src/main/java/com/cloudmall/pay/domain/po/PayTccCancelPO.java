package com.cloudmall.pay.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Pay TCC 主动取消抢占与恢复台账。 */
@TableName("pay_tcc_cancel")
public class PayTccCancelPO {
  /** 取消台账主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 支付号。 */
  @TableField("pay_no")
  public String payNo;

  /** 支付用户主键。 */
  @TableField("user_id")
  public Long userId;

  /** 关联订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 原支付金额。 */
  public BigDecimal amount;

  /** 取消台账状态。 */
  public String state;

  /** 已尝试恢复次数，上限为 31。 */
  public Integer attempts;

  /** 下一次允许恢复的时间。 */
  @TableField("next_retry_at")
  public LocalDateTime nextRetryAt;

  /** 当前恢复租约持有者。 */
  @TableField("lease_owner")
  public String leaseOwner;

  /** 当前恢复租约到期时间。 */
  @TableField("lease_until")
  public LocalDateTime leaseUntil;

  /** 最近一次错误摘要。 */
  @TableField("last_error")
  public String lastError;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
