package com.cloudmall.common.web;

import com.cloudmall.common.auth.AuthContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class AuthInterceptor implements HandlerInterceptor {
  /** 在请求进入 Controller 前建立认证上下文。 */
  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String userId = request.getHeader("X-User-Id");
    if (userId != null && !userId.isBlank()) {
      try {
        AuthContext.set(Long.valueOf(userId), request.getHeader("X-User-Role"));
      } catch (NumberFormatException exception) {
        response.setStatus(401);
        return false;
      }
    }
    return true;
  }

  /** 在请求完成后清理认证上下文。 */
  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    AuthContext.clear();
  }
}
