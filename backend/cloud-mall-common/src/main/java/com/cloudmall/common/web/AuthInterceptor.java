package com.cloudmall.common.web;

import com.cloudmall.common.auth.AuthContext;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class AuthInterceptor implements HandlerInterceptor {
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) {
            try { AuthContext.set(Long.valueOf(userId), request.getHeader("X-User-Role")); }
            catch (NumberFormatException e) { response.setStatus(401); return false; }
        }
        return true;
    }
    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) { AuthContext.clear(); }
}
