package com.cloudmall.pay;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
@SpringBootApplication(scanBasePackages={"com.cloudmall.pay","com.cloudmall.common"})
@EnableFeignClients
public class PayApplication{public static void main(String[] args){SpringApplication.run(PayApplication.class,args);}}
