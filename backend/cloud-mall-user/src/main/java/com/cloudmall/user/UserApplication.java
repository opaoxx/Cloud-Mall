package com.cloudmall.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.cloudmall.user", "com.cloudmall.common"})
@MapperScan("com.cloudmall.user.mapper")
public class UserApplication {
  /** 启动当前 Spring Boot 服务。 */
  public static void main(String[] args) {
    SpringApplication.run(UserApplication.class, args);
  }
}
