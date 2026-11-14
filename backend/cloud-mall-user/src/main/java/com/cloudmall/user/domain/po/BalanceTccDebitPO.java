package com.cloudmall.user.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** User 本地 TCC 冻结事实。 */
@TableName("balance_tcc_debit")
public class BalanceTccDebitPO {
  /** 冻结事实主键。 */
  @TableId public Long id;

  /** TCC 操作键。 */
  @TableField("operation_key")
  public String operationKey;

  /** XID。 */
  public String xid;

  /** 分支标识。 */
  @TableField("branch_id")
  public Long branchId;

  /** 用户主键。 */
  @TableField("user_id")
  public Long userId;

  /** 支付号。 */
  @TableField("pay_no")
  public String payNo;

  /** 订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 冻结金额。 */
  public BigDecimal amount;

  /** 冻结状态。 */
  public String state;

  /** 冻结前余额。 */
  @TableField("balance_before")
  public BigDecimal balanceBefore;

  /** Try 后余额。 */
  @TableField("balance_after_try")
  public BigDecimal balanceAfterTry;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
