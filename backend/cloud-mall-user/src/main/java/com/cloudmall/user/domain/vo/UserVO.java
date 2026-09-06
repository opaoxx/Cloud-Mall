package com.cloudmall.user.domain.vo;

import com.cloudmall.user.domain.po.UserPO;
import java.util.List;

/** 用户公开响应视图。 */
public class UserVO {
  /** 用户主键。 */
  public Long userId;

  /** 登录用户名。 */
  public String username;

  /** 用户角色。 */
  public String role;

  /** 用户角色列表。 */
  public List<String> roles;

  /** 用户昵称。 */
  public String nickname;

  /** 联系电话。 */
  public String phone;

  /** 头像地址。 */
  public String avatarUrl;

  /** 用户余额字符串。 */
  public String balance;

  /** 从用户持久化对象构造响应视图。 */
  public static UserVO from(UserPO user) {
    UserVO view = new UserVO();
    view.userId = user.id;
    view.username = user.username;
    view.role = user.role;
    view.roles = List.of(user.role);
    view.nickname = user.nickname == null ? "" : user.nickname;
    view.phone = user.phone == null ? "" : user.phone;
    view.avatarUrl = user.avatarUrl == null ? "" : user.avatarUrl;
    view.balance = user.balance.toPlainString();
    return view;
  }
}
