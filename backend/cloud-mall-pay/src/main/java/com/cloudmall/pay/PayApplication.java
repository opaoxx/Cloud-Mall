package com.cloudmall.pay;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.cloudmall.pay", "com.cloudmall.common"})
@EnableFeignClients
@MapperScan("com.cloudmall.pay.mapper")
public class PayApplication {
  /** 启动当前 Spring Boot 服务。 */
  public static void main(String[] args) {
    SpringApplication.run(PayApplication.class, args);
  }
}
