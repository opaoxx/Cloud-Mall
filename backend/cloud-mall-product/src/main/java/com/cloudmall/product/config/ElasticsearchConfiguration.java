package com.cloudmall.product.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@SuppressWarnings("deprecation")
@Configuration
public class ElasticsearchConfiguration {
  /** 根据配置地址创建 ES 高级客户端，由 Spring 在销毁 Bean 时关闭底层连接。 */
  @Bean(destroyMethod = "close")
  RestHighLevelClient restHighLevelClient(
      @Value("${cloudmall.product.elasticsearch.url:http://localhost:9200}") String url) {
    return new RestHighLevelClient(RestClient.builder(HttpHost.create(url)));
  }
}
