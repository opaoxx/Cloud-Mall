package com.cloudmall.pay.domain.vo;

import com.cloudmall.pay.util.UserTccLedgerBooleanDeserializer;
import com.cloudmall.pay.util.UserTccPositiveLongDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;

/** User TCC 只读状态响应。 */
public class UserTccStateVO {
  /** 用户主键。 */
  @JsonDeserialize(using = UserTccPositiveLongDeserializer.class)
  public Long userId;

  /** 支付号。 */
  public String payNo;

  /** 订单号。 */
  public String orderNo;

  /** 金额字符串。 */
  public String amount;

  /** TCC 操作键。 */
  public String operationKey;

  /** 最终账本键。 */
  public String ledgerKey;

  /** XID。 */
  public String xid;

  /** User 分支标识。 */
  @JsonDeserialize(using = UserTccPositiveLongDeserializer.class)
  public Long branchId;

  /** User 业务状态。 */
  public String state;

  /** 是否存在 APPLIED 账本。 */
  public boolean ledgerApplied;

  /** 接收侧是否观察到显式非null布尔值；只供证据核验，不进入JSON。 */
  @JsonIgnore public boolean ledgerAppliedPresent;

  /** 保留既有boolean语义，同时区分缺字段/null与明确false。 */
  @JsonSetter("ledgerApplied")
  @JsonDeserialize(using = UserTccLedgerBooleanDeserializer.class)
  public void setLedgerApplied(Boolean applied) {
    ledgerAppliedPresent = applied != null;
    ledgerApplied = Boolean.TRUE.equals(applied);
  }

  /** 返回用于载荷校验的金额。 */
  public BigDecimal amountValue() {
    return new BigDecimal(amount);
  }
}
