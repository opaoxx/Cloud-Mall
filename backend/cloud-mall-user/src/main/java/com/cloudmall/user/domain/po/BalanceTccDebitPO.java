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

  @TableField("operation_key")
  /** TCC 操作键。 */
  public String operationKey;

  /** XID。 */
  public String xid;

  @TableField("branch_id")
  /** 分支标识。 */
  public Long branchId;

  @TableField("user_id")
  /** 用户主键。 */
  public Long userId;

  @TableField("pay_no")
  /** 支付号。 */
  public String payNo;

  @TableField("order_no")
  /** 订单号。 */
  public String orderNo;

  /** 冻结金额。 */
  public BigDecimal amount;

  /** 冻结状态。 */
  public String state;

  @TableField("balance_before")
  /** 冻结前余额。 */
  public BigDecimal balanceBefore;

  @TableField("balance_after_try")
  /** Try 后余额。 */
  public BigDecimal balanceAfterTry;

  @TableField("created_at")
  /** 创建时间。 */
  public LocalDateTime createdAt;

  @TableField("updated_at")
  /** 更新时间。 */
  public LocalDateTime updatedAt;
}
