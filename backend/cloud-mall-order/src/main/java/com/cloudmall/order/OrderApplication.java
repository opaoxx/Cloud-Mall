package com.cloudmall.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.cloudmall.order", "com.cloudmall.common"})
@EnableFeignClients
@MapperScan("com.cloudmall.order.mapper")
@EnableScheduling
public class OrderApplication {
  /** 启动当前 Spring Boot 服务。 */
  public static void main(String[] args) {
    SpringApplication.run(OrderApplication.class, args);
  }
}
