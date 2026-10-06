package com.cloudmall.pay.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Pay 内部 TCC coordinator 一次性执行事实。 */
@TableName("pay_tcc_execution")
public class PayTccExecutionPO {
  /** 执行事实主键。 */
  @TableId public Long id;

  /** 支付号。 */
  @TableField("pay_no")
  public String payNo;

  /** Seata 全局事务标识，begin 未确认时为空。 */
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

  /** 执行状态。 */
  public String state;

  /** 决议方向，NONE、COMMIT 或 ROLLBACK。 */
  public String decision;

  /** 最近错误摘要。 */
  @TableField("last_error")
  public String lastError;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
