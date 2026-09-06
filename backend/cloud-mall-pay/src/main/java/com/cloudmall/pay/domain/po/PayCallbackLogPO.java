package com.cloudmall.pay.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 支付回调审计持久化对象。 */
@TableName("pay_callback_log")
public class PayCallbackLogPO {
  /** 回调日志主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 支付单号。 */
  @TableField("pay_no")
  public String payNo;

  /** 回调幂等标识。 */
  @TableField("callback_id")
  public String callbackId;

  /** 回调处理状态。 */
  @TableField("callback_status")
  public String callbackStatus;

  /** 原始载荷 JSON。 */
  public String payload;

  /** 处理完成时间。 */
  @TableField("processed_at")
  public LocalDateTime processedAt;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;
}
