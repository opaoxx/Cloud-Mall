package com.cloudmall.order;

import com.cloudmall.common.auth.AuthContext;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignAuthConfiguration {
    @Bean public RequestInterceptor authHeaders() { return template -> { if (AuthContext.userId() != null) { template.header("X-User-Id", String.valueOf(AuthContext.userId())); template.header("X-User-Role", AuthContext.role()); } }; }
}
