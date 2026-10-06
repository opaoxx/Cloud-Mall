package com.cloudmall.pay.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Seata 1.5.2 官方 TCC Fence 记录；主键由 xid 和 branchId 组成。 */
@TableName("tcc_fence_log")
public class PayTccFencePO {
  /** 全局事务标识。 */
  public String xid;

  /** 分支标识。 */
  @TableField("branch_id")
  public Long branchId;

  /** TCC 动作名称。 */
  @TableField("action_name")
  public String actionName;

  /** Fence 状态。 */
  public Integer status;

  /** 创建时间。 */
  @TableField("gmt_create")
  public LocalDateTime gmtCreate;

  /** 更新时间。 */
  @TableField("gmt_modified")
  public LocalDateTime gmtModified;
}
