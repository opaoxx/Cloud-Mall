package com.cloudmall.pay.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Pay LocalTCC receipt 持久化事实。 */
@TableName("pay_tcc_receipt")
public class PayTccReceiptPO {
  /** Receipt 主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 支付号。 */
  @TableField("pay_no")
  public String payNo;

  /** Seata 全局事务标识。 */
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

  /** Pay 分支标识，CLAIMED 阶段为空。 */
  @TableField("branch_id")
  public Long branchId;

  /** Receipt 状态。 */
  public String state;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
