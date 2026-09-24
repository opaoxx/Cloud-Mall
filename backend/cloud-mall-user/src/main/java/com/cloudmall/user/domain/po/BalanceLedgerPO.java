package com.cloudmall.user.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 用户余额扣款和退款账本持久化对象。 */
@TableName("balance_ledger")
public class BalanceLedgerPO {
  /** 账本主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 用户主键。 */
  @TableField("user_id")
  public Long userId;

  /** 幂等操作键。 */
  @TableField("operation_key")
  public String operationKey;

  /** 操作类型，DEBIT 或 REFUND。 */
  @TableField("operation_type")
  public String operationType;

  /** 退款对应的原扣款操作键。 */
  @TableField("original_operation_key")
  public String originalOperationKey;

  /** 支付号。 */
  @TableField("pay_no")
  public String payNo;

  /** 订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 操作金额。 */
  public BigDecimal amount;

  /** 操作状态，APPLIED 或 REJECTED。 */
  public String state;

  /** 操作前余额。 */
  @TableField("balance_before")
  public BigDecimal balanceBefore;

  /** 操作后余额。 */
  @TableField("balance_after")
  public BigDecimal balanceAfter;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;
}
