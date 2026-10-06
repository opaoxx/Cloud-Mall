package com.cloudmall.user.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 持久接收凭据，防止 HTTP 重放再次注册分支。 */
@TableName("user_tcc_receive")
public class UserTccReceivePO {
  /** 接收记录主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 不可变支付号。 */
  @TableField("pay_no")
  public String payNo;

  /** 不可变全局事务。 */
  public String xid;

  /** POST 已观察到的分支，未知时为空。 */
  @TableField("branch_id")
  public Long branchId;

  /** 原用户。 */
  @TableField("user_id")
  public Long userId;

  /** 原订单。 */
  @TableField("order_no")
  public String orderNo;

  /** 原金额。 */
  public BigDecimal amount;

  /** TCC 操作键。 */
  @TableField("operation_key")
  public String operationKey;

  /** 最终账本键。 */
  @TableField("ledger_key")
  public String ledgerKey;

  /** 接收 CLAIMED 标记，不代表资金终态。 */
  public String state;

  /** 接收时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 分支观察时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
