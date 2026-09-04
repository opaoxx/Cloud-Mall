package com.cloudmall.cart;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
@SpringBootApplication(scanBasePackages={"com.cloudmall.cart","com.cloudmall.common"})
@EnableFeignClients
public class CartApplication{public static void main(String[] args){SpringApplication.run(CartApplication.class,args);}}
