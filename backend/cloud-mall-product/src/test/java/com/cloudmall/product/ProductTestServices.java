package com.cloudmall.product;

import static org.mockito.Mockito.mock;

import com.cloudmall.product.controller.ProductController;
import com.cloudmall.product.mapper.ProductSqlMapper;
import com.cloudmall.product.service.ProductIndexOutboxService;
import com.cloudmall.product.service.impl.CategoryServiceImpl;
import com.cloudmall.product.service.impl.ProductCatalogReader;
import com.cloudmall.product.service.impl.ProductManagementServiceImpl;
import com.cloudmall.product.service.impl.ProductQueryServiceImpl;
import com.cloudmall.product.service.impl.SeckillActivityServiceImpl;
import com.cloudmall.product.util.ProductValueCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** 将原测试的远端替身装配到真实Controller和拆分后的业务入口。 */
final class ProductTestServices {
  /** 禁止实例化测试装配工具。 */
  private ProductTestServices() {}

  /** 使用原四项依赖建立真实业务调用链。 */
  static ProductController controller(
      ProductSqlMapper mapper,
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      ProductIndexOutboxService outbox) {
    ProductValueCodec codec = new ProductValueCodec(objectMapper);
    ProductCatalogReader reader = new ProductCatalogReader(mapper, codec);
    return new ProductController(
        new ProductQueryServiceImpl(mapper, reader, codec),
        new ProductManagementServiceImpl(mapper, reader, outbox, codec),
        new CategoryServiceImpl(mapper),
        new SeckillActivityServiceImpl(mapper, redis));
  }

  /** 为仅验证数据库读取失败的原用例补齐未使用的依赖。 */
  static ProductController controller(ProductSqlMapper mapper) {
    return controller(
        mapper,
        mock(StringRedisTemplate.class),
        new ObjectMapper(),
        mock(ProductIndexOutboxService.class));
  }

  /** 将既有可选模式测试设置定位到实际查询服务。 */
  static ProductQueryServiceImpl queries(ProductController controller) {
    return (ProductQueryServiceImpl)
        ReflectionTestUtils.getField(controller, "productQueryService");
  }
}
