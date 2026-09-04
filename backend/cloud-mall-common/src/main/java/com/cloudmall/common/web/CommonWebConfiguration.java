package com.cloudmall.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CommonWebConfiguration implements WebMvcConfigurer {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer moneyAsString() {
        return builder -> builder.serializerByType(BigDecimal.class, new JsonSerializer<BigDecimal>() {
            @Override public void serialize(BigDecimal value, JsonGenerator gen, com.fasterxml.jackson.databind.SerializerProvider provider) throws IOException {
                gen.writeString(value.toPlainString());
            }
        });
    }

    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(new AuthInterceptor()).addPathPatterns("/**"); }
}
