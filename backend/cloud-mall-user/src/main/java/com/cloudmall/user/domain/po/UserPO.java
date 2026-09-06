package com.cloudmall.user.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 用户事实数据持久化对象。 */
@TableName("mall_user")
public class UserPO {
  /** 用户主键。 */
  @TableId(type = IdType.INPUT)
  public Long id;

  /** 登录用户名。 */
  public String username;

  /** 密码摘要。 */
  @TableField("password_hash")
  public String passwordHash;

  /** 用户角色。 */
  public String role;

  /** 用户余额。 */
  public BigDecimal balance;

  /** 用户昵称。 */
  public String nickname;

  /** 用户联系电话。 */
  public String phone;

  /** 用户头像地址。 */
  @TableField("avatar_url")
  public String avatarUrl;

  /** 用户状态。 */
  public Integer status;

  /** 创建时间。 */
  @TableField("created_at")
  public LocalDateTime createdAt;

  /** 更新时间。 */
  @TableField("updated_at")
  public LocalDateTime updatedAt;
}
