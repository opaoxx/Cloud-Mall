package com.cloudmall.product;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"com.cloudmall.product","com.cloudmall.common"})
public class ProductApplication{public static void main(String[] args){SpringApplication.run(ProductApplication.class,args);}}
