package com.cloudmall.common.auth;

public final class AuthContext {
  /** 当前线程保存的用户 ID。 */
  private static final ThreadLocal<Long> USER = new ThreadLocal<>();

  /** 当前线程保存的用户角色。 */
  private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

  /** 禁止实例化认证上下文工具类。 */
  private AuthContext() {}

  /** 写入当前请求的认证上下文。 */
  public static void set(Long userId, String role) {
    USER.set(userId);
    ROLE.set(role == null || role.isBlank() ? "USER" : role);
  }

  /** 读取当前请求的用户 ID。 */
  public static Long userId() {
    return USER.get();
  }

  /** 读取当前请求的用户角色。 */
  public static String role() {
    return ROLE.get();
  }

  /** 判断当前请求是否已认证。 */
  public static boolean authenticated() {
    return USER.get() != null;
  }

  /** 判断当前用户是否具备管理员角色。 */
  public static boolean isAdmin() {
    return "ADMIN".equals(ROLE.get());
  }

  /** 获取当前用户 ID，未认证时抛出业务异常。 */
  public static Long requireUserId() {
    if (USER.get() == null) {
      throw new com.cloudmall.common.error.BizException(
          com.cloudmall.common.error.ErrorCodes.UNAUTHORIZED, "请先登录", 401);
    }
    return USER.get();
  }

  /** 校验当前用户是否具备管理员权限。 */
  public static void requireAdmin() {
    requireUserId();
    if (!isAdmin()) {
      throw new com.cloudmall.common.error.BizException(
          com.cloudmall.common.error.ErrorCodes.FORBIDDEN, "需要管理员权限", 403);
    }
  }

  /** 清理当前线程的认证上下文。 */
  public static void clear() {
    USER.remove();
    ROLE.remove();
  }
}
