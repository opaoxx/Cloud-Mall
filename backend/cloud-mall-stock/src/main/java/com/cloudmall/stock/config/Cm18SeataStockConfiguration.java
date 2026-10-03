package com.cloudmall.stock.config;

import com.cloudmall.stock.mapper.Cm18SeataStockDemoMapper;
import com.cloudmall.stock.service.Cm18SeataStockDemoService;
import com.cloudmall.stock.service.Cm18SeataStockDemoOrchestrator;
import com.cloudmall.stock.service.impl.Cm18SeataStockDemoServiceImpl;
import com.cloudmall.stock.service.impl.Cm18SeataStockDemoOrchestratorImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 仅在非生产且显式开启时注册 CM-18 Stock 演练 Bean。 */
@Configuration
@Profile("!prod")
@ConditionalOnProperty(name = "cloudmall.seata.cm18.enabled", havingValue = "true")
public class Cm18SeataStockConfiguration {
  /** 创建独立的 Stock 演练本地事务 Bean。 */
  @Bean
  public Cm18SeataStockDemoService cm18SeataStockDemoService(
      Cm18SeataStockDemoMapper demoMapper) {
    return new Cm18SeataStockDemoServiceImpl(demoMapper);
  }

  /** 创建事务代理外的 CM-18 编排 Bean。 */
  @Bean
  public Cm18SeataStockDemoOrchestrator cm18SeataStockDemoOrchestrator(
      Cm18SeataStockDemoService branchService,
      @Value("${cloudmall.seata.cm18.pause-after-stock-write-ms:0}") int pauseAfterWriteMs) {
    return new Cm18SeataStockDemoOrchestratorImpl(branchService, pauseAfterWriteMs);
  }
}
