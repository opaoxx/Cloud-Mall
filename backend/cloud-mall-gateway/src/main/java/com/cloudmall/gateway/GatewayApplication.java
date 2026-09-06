package com.cloudmall.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {
  /** 启动当前 Spring Boot 服务。 */
  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
