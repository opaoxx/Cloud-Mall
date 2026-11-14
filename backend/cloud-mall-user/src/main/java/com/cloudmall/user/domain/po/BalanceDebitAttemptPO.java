package com.cloudmall.user.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** LEGACY/TCC 余额扣款共享归属事实。 */
@TableName("balance_debit_attempt")
public class BalanceDebitAttemptPO {
  /** 归属主键。 */
  @TableId public Long id;

  /** 支付号。 */
  @TableField("pay_no")
  public String payNo;

  /** 业务操作键。 */
  @TableField("operation_key")
  public String operationKey;

  /** 账本唯一操作键，TCC 使用 payNo，Legacy 使用 operationKey。 */
  @TableField("ledger_key")
  public String ledgerKey;

  /** 扣款协议。 */
  public String mode;

  /** 事务标识。 */
  public String xid;

  /** 分支标识。 */
  @TableField("branch_id")
  public Long branchId;

  /** 用户主键。 */
  @TableField("user_id")
  public Long userId;

  /** 订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 扣款金额。 */
  public BigDecimal amount;

  /** 归属状态。 */
  public String state;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
