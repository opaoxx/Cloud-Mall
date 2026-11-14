package com.cloudmall.stock.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 以订单号全局认领的普通库存预扣请求。 */
@TableName("stock_reservation_request")
public class StockReservationRequestPO {
  /** 请求内部主键。 */
  @TableId public Long id;

  /** 全局唯一的预扣订单号。 */
  @TableField("order_no")
  public String orderNo;

  /** 库存使用场景。 */
  public String scene;

  /** 规范化 SKU 与数量 JSON 快照。 */
  @TableField("items_snapshot")
  public String itemsSnapshot;

  /** 快照 SHA-256。 */
  @TableField("items_hash")
  public String itemsHash;

  /** INIT、RESERVED、REJECTED、CONFIRMED 或 ROLLED_BACK。 */
  public String status;

  /** 最终业务结果码。 */
  @TableField("result_code")
  public String resultCode;

  /** 最近处理失败原因。 */
  @TableField("last_error")
  public String lastError;

  /** 下次允许恢复的时间。 */
  @TableField("next_retry_at")
  public LocalDateTime nextRetryAt;

  /** 当前恢复任务实例标识。 */
  @TableField("lease_owner")
  public String leaseOwner;

  /** 恢复租约到期时间。 */
  @TableField("lease_until")
  public LocalDateTime leaseUntil;

  /** 请求创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 请求最后更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;

  /** 创建空的库存预扣请求对象。 */
  public StockReservationRequestPO() {}
}
