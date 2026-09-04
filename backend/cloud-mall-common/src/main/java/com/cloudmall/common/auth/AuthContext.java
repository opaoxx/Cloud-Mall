package com.cloudmall.common.auth;
public final class AuthContext {
    private static final ThreadLocal<Long> USER = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();
    private AuthContext() {}
    public static void set(Long id, String role) { USER.set(id); ROLE.set(role == null || role.isBlank() ? "USER" : role); }
    public static Long userId() { return USER.get(); }
    public static String role() { return ROLE.get(); }
    public static boolean authenticated() { return USER.get() != null; }
    public static boolean isAdmin() { return "ADMIN".equals(ROLE.get()); }
    public static Long requireUserId() { if (USER.get() == null) throw new com.cloudmall.common.error.BizException(com.cloudmall.common.error.ErrorCodes.UNAUTHORIZED, "请先登录", 401); return USER.get(); }
    public static void requireAdmin() { requireUserId(); if (!isAdmin()) throw new com.cloudmall.common.error.BizException(com.cloudmall.common.error.ErrorCodes.FORBIDDEN, "需要管理员权限", 403); }
    public static void clear() { USER.remove(); ROLE.remove(); }
}
