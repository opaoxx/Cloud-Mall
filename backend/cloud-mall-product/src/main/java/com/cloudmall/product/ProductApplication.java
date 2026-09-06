package com.cloudmall.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.cloudmall.product", "com.cloudmall.common"})
@MapperScan("com.cloudmall.product.mapper")
public class ProductApplication {
  /** 启动当前 Spring Boot 服务。 */
  public static void main(String[] args) {
    SpringApplication.run(ProductApplication.class, args);
  }
}
