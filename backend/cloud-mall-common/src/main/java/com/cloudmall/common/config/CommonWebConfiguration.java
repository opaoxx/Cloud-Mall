package com.cloudmall.common.config;

import com.cloudmall.common.web.AuthInterceptor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import java.io.IOException;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CommonWebConfiguration implements WebMvcConfigurer {
  /** 配置金额的 JSON 字符串序列化。 */
  @Bean
  public Jackson2ObjectMapperBuilderCustomizer moneyAsString() {
    return builder ->
        builder.serializerByType(
            BigDecimal.class,
            new JsonSerializer<BigDecimal>() {
              /** 执行 serialize 的内部辅助逻辑。 */
              @Override
              public void serialize(
                  BigDecimal value,
                  JsonGenerator gen,
                  com.fasterxml.jackson.databind.SerializerProvider provider)
                  throws IOException {
                gen.writeString(value.toPlainString());
              }
            });
  }

  /** 注册公共 MVC 拦截器。 */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new AuthInterceptor()).addPathPatterns("/**");
  }
}
