package com.cloudmall.order.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 普通订单跨服务库存预扣的持久化恢复意图。 */
@TableName("order_reservation_intent")
public class OrderReservationIntentPO {
  /** 意图内部主键。 */
  @TableId public Long id;

  /** 原始库存预扣订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 创建意图的用户 ID。 */
  @TableField("user_id")
  public Long userId;

  /** 用户请求的幂等键。 */
  @TableField("idempotency_key")
  public String idempotencyKey;

  /** 服务端校验后的 SKU 与数量 JSON 快照。 */
  @TableField("items_snapshot")
  public String itemsSnapshot;

  /** INIT、RESERVED、COMMITTED 或 RELEASED。 */
  public String status;

  /** 最近一次恢复失败原因。 */
  @TableField("last_error")
  public String lastError;

  /** 下次允许恢复尝试的时间。 */
  @TableField("next_retry_at")
  public LocalDateTime nextRetryAt;

  /** 意图创建时间，按 Asia/Shanghai 存储。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 意图最后更新时间，按 Asia/Shanghai 存储。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;

  /** 创建空的预扣意图持久化对象。 */
  public OrderReservationIntentPO() {}
}
