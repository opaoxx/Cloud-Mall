package com.cloudmall.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.cloudmall.user", "com.cloudmall.common"})
public class UserApplication {
  /** 启动当前 Spring Boot 服务。 */
  public static void main(String[] args) {
    SpringApplication.run(UserApplication.class, args);
  }
}
