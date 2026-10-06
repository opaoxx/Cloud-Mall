package com.cloudmall.pay.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Pay TCC 交付任务持久化对象。 */
@TableName("pay_tcc_delivery")
public class PayTccDeliveryPO {
  /** 交付任务主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 支付号。 */
  @TableField("pay_no")
  public String payNo;

  /** 原始全局事务标识。 */
  public String xid;

  /** 支付用户主键。 */
  @TableField("user_id")
  public Long userId;

  /** 关联订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 原始扣款金额。 */
  public BigDecimal amount;

  /** 交付任务状态。 */
  public String state;

  /** 唯一退款操作键。 */
  @TableField("refund_operation_key")
  public String refundOperationKey;

  /** 已尝试次数，数据库将其限制为最多 31。 */
  public Integer attempts;

  /** 下一次允许执行任务的时间。 */
  @TableField("next_retry_at")
  public LocalDateTime nextRetryAt;

  /** 当前租约持有者。 */
  @TableField("lease_owner")
  public String leaseOwner;

  /** 当前租约到期时间。 */
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
