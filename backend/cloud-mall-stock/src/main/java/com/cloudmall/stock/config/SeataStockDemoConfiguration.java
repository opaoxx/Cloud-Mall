package com.cloudmall.stock.config;

import com.cloudmall.stock.mapper.SeataStockDemoMapper;
import com.cloudmall.stock.service.SeataStockDemoOrchestrator;
import com.cloudmall.stock.service.SeataStockDemoService;
import com.cloudmall.stock.service.impl.seata.SeataStockDemoOrchestratorImpl;
import com.cloudmall.stock.service.impl.seata.SeataStockDemoServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 仅在非生产且显式开启时注册 CM-18 Stock 演练 Bean。 */
@Configuration("cm18SeataStockConfiguration")
@Profile("!prod")
@ConditionalOnProperty(name = "cloudmall.seata.cm18.enabled", havingValue = "true")
public class SeataStockDemoConfiguration {
  /** 创建独立的 Stock 演练本地事务 Bean。 */
  @Bean("cm18SeataStockDemoService")
  public SeataStockDemoService seataStockDemoService(SeataStockDemoMapper demoMapper) {
    return new SeataStockDemoServiceImpl(demoMapper);
  }

  /** 创建事务代理外的 CM-18 编排 Bean。 */
  @Bean("cm18SeataStockDemoOrchestrator")
  public SeataStockDemoOrchestrator seataStockDemoOrchestrator(
      SeataStockDemoService branchService,
      @Value("${cloudmall.seata.cm18.pause-after-stock-write-ms:0}") int pauseAfterWriteMs) {
    return new SeataStockDemoOrchestratorImpl(branchService, pauseAfterWriteMs);
  }
}
