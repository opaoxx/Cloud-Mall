package com.cloudmall.pay.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Pay TCC 派发不可变事实。 */
@TableName("pay_tcc_dispatch")
public class PayTccDispatchPO {
  /** 派发主键。 */
  @TableId public Long id;

  /** 支付号。 */
  @TableField("pay_no")
  public String payNo;

  /** 全局事务标识。 */
  public String xid;

  /** 用户主键。 */
  @TableField("user_id")
  public Long userId;

  /** 订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 扣款金额。 */
  public BigDecimal amount;

  /** TCC 操作键。 */
  @TableField("operation_key")
  public String operationKey;

  /** 最终账本键。 */
  @TableField("ledger_key")
  public String ledgerKey;

  /** 派发镜像状态。 */
  public String state;

  /** POST 发出时间。 */
  @TableField("post_attempted_at")
  public LocalDateTime postAttemptedAt;

  /** User 首次观察到的分支标识。 */
  @TableField("user_branch_id")
  public Long userBranchId;

  /** 最近一次未知原因。 */
  @TableField("last_error")
  public String lastError;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
