package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.product.config.ProductReadExceptionHandler;
import com.cloudmall.product.controller.ProductController;
import com.cloudmall.product.mapper.ProductSqlMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductReadDependencyFailureTest {
  private final ProductSqlMapper mapper = mock(ProductSqlMapper.class);
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final MockMvc mvc = mvc();

  @Test
  void connectionAcquisitionFailureIs503AndCounted() throws Exception {
    when(mapper.countProducts(any(), any(), any()))
        .thenThrow(new CannotGetJdbcConnectionException("pool exhausted", new SQLException("timeout")));

    mvc.perform(get("/api/products"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PRODUCT_QUERY_UNAVAILABLE"));

    assertEquals(1.0, metrics.find("cloudmall.product.read.failures")
        .tag("reason", "connection_acquire").counter().count());
  }

  @Test
  void sqlTimeoutIs503ButSyntaxErrorRemains500() throws Exception {
    when(mapper.findProduct(42L))
        .thenThrow(new QueryTimeoutException("read timed out"))
        .thenThrow(new BadSqlGrammarException("read", "bad sql", new SQLException("syntax")));

    mvc.perform(get("/api/products/42"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("PRODUCT_QUERY_UNAVAILABLE"));
    mvc.perform(get("/api/products/42"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"));
  }

  private MockMvc mvc() {
    ProductController controller = new ProductController();
    ReflectionTestUtils.setField(controller, "productSqlMapper", mapper);
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new ProductReadExceptionHandler(metrics), new GlobalExceptionHandler())
        .build();
  }
}
