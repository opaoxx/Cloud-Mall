package com.cloudmall.order.config;

import com.cloudmall.common.auth.AuthContext;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignAuthConfiguration {
  /**
   * 保留方法参数显式身份，仅无显式用户时注入当前线程上下文。
   *
   * @return 不追加晚期身份到原用户请求的Feign拦截器
   */
  @Bean
  public RequestInterceptor authHeaders() {
    return template -> {
      // 1. 显式原用户及其角色优先，提交后不混入晚期线程身份。
      if (template.headers().keySet().stream()
          .anyMatch(header -> header.equalsIgnoreCase("X-User-Id"))) return;
      // 2. 没有显式用户时保持原上下文注入，匿名线程不构造身份。
      Long currentUserId = AuthContext.userId();
      if (currentUserId == null) return;
      template.header("X-User-Id", String.valueOf(currentUserId));
      // 3. 显式角色同样保持原值，不追加第二个角色。
      if (template.headers().keySet().stream()
          .noneMatch(header -> header.equalsIgnoreCase("X-User-Role")))
        template.header("X-User-Role", AuthContext.role());
    };
  }
}
