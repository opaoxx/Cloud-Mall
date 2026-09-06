package com.cloudmall.stock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.cloudmall.stock", "com.cloudmall.common"})
@EnableScheduling
public class StockApplication {
  /** 启动当前 Spring Boot 服务。 */
  public static void main(String[] args) {
    SpringApplication.run(StockApplication.class, args);
  }
}
