package com.cloudmall.common.web;
import com.cloudmall.common.auth.AuthContext; import org.springframework.web.servlet.*; import javax.servlet.http.*;
public class AuthInterceptor implements HandlerInterceptor {public boolean preHandle(HttpServletRequest r,HttpServletResponse s,Object h){String u=r.getHeader("X-User-Id"),role=r.getHeader("X-User-Role"); if(u!=null) AuthContext.set(Long.valueOf(u),role==null?"USER":role); return true;} public void afterCompletion(HttpServletRequest r,HttpServletResponse s,Object h,Exception e){AuthContext.clear();}}
