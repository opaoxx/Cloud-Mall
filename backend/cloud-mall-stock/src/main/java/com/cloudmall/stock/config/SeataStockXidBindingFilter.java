package com.cloudmall.stock.config;

import io.seata.core.context.RootContext;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 只为 CM-18 端点安全绑定测试 XID，离开请求后始终解除本地绑定。 */
@Component("cm18SeataXidBindingFilter")
@Profile("!prod")
@ConditionalOnProperty(name = "cloudmall.seata.cm18.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SeataStockXidBindingFilter extends OncePerRequestFilter {
  /** 仅允许演练接口绑定来自 Feign 的 Seata XID。 */
  private static final String PATH = "/api/internal/cm18/seata/demo/branch";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!PATH.equals(request.getRequestURI())) {
      filterChain.doFilter(request, response);
      return;
    }
    if (!"cloud-mall-cm18-test".equals(request.getHeader("X-Internal-Service"))) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN, "CM18 internal header is invalid");
      return;
    }
    String headerXid = request.getHeader("TX_XID");
    if (headerXid == null || headerXid.trim().isEmpty()) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "TX_XID is required");
      return;
    }
    String currentXid = RootContext.getXID();
    boolean boundByFilter = false;
    if (currentXid == null) {
      RootContext.bind(headerXid);
      boundByFilter = true;
    } else if (!currentXid.equals(headerXid)) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Seata XID binding is invalid");
      return;
    }
    try {
      filterChain.doFilter(request, response);
    } finally {
      if (boundByFilter) {
        RootContext.unbind();
      }
    }
  }
}
