package com.cloudmall.pay.config;

import com.cloudmall.common.auth.AuthContext;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 将当前认证上下文中的用户身份透传给原Feign调用，不生成新的身份。 */
@Configuration
public class FeignAuthConfiguration {
  /** 有当前用户时添加原X-User-Id和X-User-Role请求头；无用户时不添加身份头。 */
  @Bean
  public RequestInterceptor authHeaders() {
    return template -> {
      if (AuthContext.userId() != null) {
        template.header("X-User-Id", String.valueOf(AuthContext.userId()));
        template.header("X-User-Role", AuthContext.role());
      }
    };
  }
}
