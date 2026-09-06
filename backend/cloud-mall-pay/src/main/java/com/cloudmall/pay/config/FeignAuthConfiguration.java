package com.cloudmall.pay.config;

import com.cloudmall.common.auth.AuthContext;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignAuthConfiguration {
  @Bean
  /** 执行 authHeaders 相关操作。 */
  public RequestInterceptor authHeaders() {
    return template -> {
      if (AuthContext.userId() != null) {
        template.header("X-User-Id", String.valueOf(AuthContext.userId()));
        template.header("X-User-Role", AuthContext.role());
      }
    };
  }
}
