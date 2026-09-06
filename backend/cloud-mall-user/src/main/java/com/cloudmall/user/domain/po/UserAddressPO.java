package com.cloudmall.user.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 用户收货地址持久化对象。 */
@TableName("user_address")
public class UserAddressPO {
  /** 地址主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 所属用户主键。 */
  @TableField("user_id")
  public Long userId;

  /** 收货人姓名。 */
  @TableField("receiver_name")
  public String receiverName;

  /** 收货人电话。 */
  @TableField("receiver_phone")
  public String receiverPhone;

  /** 地区及详细地址。 */
  @TableField("region_detail")
  public String regionDetail;

  /** 默认地址标志。 */
  @TableField("is_default")
  public Boolean defaultAddress;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
