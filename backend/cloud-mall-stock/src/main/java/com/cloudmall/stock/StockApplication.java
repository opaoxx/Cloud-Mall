package com.cloudmall.stock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"com.cloudmall.stock","com.cloudmall.common"})
public class StockApplication{public static void main(String[] args){SpringApplication.run(StockApplication.class,args);}}
