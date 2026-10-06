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

  @TableField("pay_no")
  /** 支付号。 */
  public String payNo;

  @TableField("operation_key")
  /** 业务操作键。 */
  public String operationKey;

  /** 账本唯一操作键，TCC 使用 payNo，Legacy 使用 operationKey。 */
  @TableField("ledger_key")
  public String ledgerKey;

  /** 扣款协议。 */
  public String mode;

  /** 事务标识。 */
  public String xid;

  @TableField("branch_id")
  /** 分支标识。 */
  public Long branchId;

  @TableField("user_id")
  /** 用户主键。 */
  public Long userId;

  @TableField("order_no")
  /** 订单号。 */
  public String orderNo;

  /** 扣款金额。 */
  public BigDecimal amount;

  /** 归属状态。 */
  public String state;

  @TableField("created_at")
  /** 创建时间。 */
  public LocalDateTime createdAt;

  @TableField("updated_at")
  /** 更新时间。 */
  public LocalDateTime updatedAt;
}
