package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.cloudmall.common.config.CommonWebConfiguration;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.product.config.ProductReadExceptionHandler;
import com.cloudmall.product.controller.ProductController;
import com.cloudmall.product.mapper.ProductIndexOutboxMapper;
import com.cloudmall.product.mapper.ProductSqlMapper;
import com.cloudmall.product.service.ProductIndexOutboxService;
import com.cloudmall.product.service.ProductManagementService;
import com.cloudmall.product.service.impl.CategoryServiceImpl;
import com.cloudmall.product.service.impl.ProductCatalogReader;
import com.cloudmall.product.service.impl.ProductManagementServiceImpl;
import com.cloudmall.product.service.impl.ProductQueryServiceImpl;
import com.cloudmall.product.service.impl.SeckillActivityServiceImpl;
import com.cloudmall.product.util.ProductValueCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 使用显式独立MySQL端口和UUID数据库验证真实MVC、事务、Mapper与outbox。 */
class ProductRefactoringMySqlIT {
  /** 显式测试服务器的管理连接URL。 */
  private static String adminUrl;

  /** 本类成功创建并独占的数据库。 */
  private static String ownedDatabase;

  /** 仅指向本类数据库的连接配置。 */
  private static DriverManagerDataSource dataSource;

  /** 实际Spring Web和事务上下文。 */
  private static AnnotationConfigWebApplicationContext context;

  /** 本类数据库的验证入口。 */
  private static JdbcTemplate jdbcTemplate;

  /** 原表结构中需要逐例清理的表。 */
  private static List<String> ownedTables;

  /** 实际HTTP入口。 */
  private MockMvc mvc;

  /** 应用响应序列化配置。 */
  private ObjectMapper objectMapper;

  /** 活动Redis边界替身。 */
  private StringRedisTemplate redis;

  /** 当前用例的活动值操作替身。 */
  private ValueOperations<String, String> values;

  @BeforeAll
  static void createOwnedDatabaseAndApplication() throws Exception {
    String port =
        Objects.requireNonNull(
            System.getProperty("cloudmall.product.refactor.mysql.port"),
            "Explicit isolated MySQL port is required");
    int parsedPort = Integer.parseInt(port);
    if (parsedPort < 1 || parsedPort > 65535) throw new IllegalArgumentException("Invalid port");
    String options =
        "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
            + "&characterEncoding=utf8&connectTimeout=2000&socketTimeout=5000";
    String server = "jdbc:mysql://127.0.0.1:" + parsedPort + "/";
    adminUrl = server + options;
    String candidate = "cm_refactor_product_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = DriverManager.getConnection(adminUrl, "root", "root");
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE DATABASE `" + candidate + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
      ownedDatabase = candidate;
    }
    dataSource = new DriverManagerDataSource(server + ownedDatabase + options, "root", "root");
    jdbcTemplate = new JdbcTemplate(dataSource);
    Path repository = Path.of("").toAbsolutePath();
    while (repository != null
        && !Files.isRegularFile(repository.resolve("backend/database/schema.sql")))
      repository = repository.getParent();
    String schema =
        Files.readString(Objects.requireNonNull(repository).resolve("backend/database/schema.sql"));
    int beginning = schema.indexOf("USE cloudmall_product;") + "USE cloudmall_product;".length();
    int end = schema.indexOf("USE cloudmall_stock;", beginning);
    for (String statement : schema.substring(beginning, end).split(";")) {
      if (!statement.isBlank()) jdbcTemplate.execute(statement);
    }
    ownedTables = jdbcTemplate.queryForList("SHOW TABLES", String.class);
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.register(Application.class);
    context.refresh();
    assertTrue(AopUtils.isAopProxy(context.getBean(ProductManagementService.class)));
    System.out.println("PRODUCT_REFACTOR_OWNED_DATABASE=" + ownedDatabase);
  }

  @AfterAll
  static void closeAndDropOwnedDatabase() throws Exception {
    try {
      if (context != null) context.close();
    } finally {
      if (ownedDatabase != null) {
        if (!ownedDatabase.matches("cm_refactor_product_[a-f0-9]{32}"))
          throw new IllegalStateException("Unexpected owned database name");
        try (Connection connection = DriverManager.getConnection(adminUrl, "root", "root");
            Statement statement = connection.createStatement()) {
          statement.execute("DROP DATABASE `" + ownedDatabase + "`");
        }
        System.out.println("PRODUCT_REFACTOR_DATABASE_REMOVED=" + ownedDatabase);
      }
    }
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void prepareIndependentCase() {
    for (String table : ownedTables) {
      if (!table.matches("[a-z_]+")) throw new IllegalStateException("Unexpected table");
      jdbcTemplate.execute("DELETE FROM `" + table + "`");
    }
    objectMapper = context.getBean(ObjectMapper.class);
    redis = context.getBean(StringRedisTemplate.class);
    reset(redis);
    values = mock(ValueOperations.class);
    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(redis.opsForHash()).thenReturn(hashes);
    when(values.get(anyString())).thenReturn("7");
    mvc = MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  void productLifecycleUsesTypedJsonAndCommitsMatchingOutboxVersions() throws Exception {
    JsonNode created = createProduct();
    assertTrue(created.path("id").isTextual());
    assertEquals("12.3", created.path("price").asText());
    long productId = created.path("id").asLong();
    assertEquals(1L, count("product"));
    assertEquals(1L, count("product_sku"));
    assertEquals(1L, count("product_parameter"));
    assertEquals(1L, count("product_index_outbox"));
    mvc.perform(get("/api/products/" + productId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.price").value("12.30"))
        .andExpect(jsonPath("$.data.skus[0].productId").value(Long.toString(productId)))
        .andExpect(jsonPath("$.data.parameters[0].productId").value(Long.toString(productId)));
    mvc.perform(get("/api/products").param("page", "0").param("pageSize", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.pageSize").value(100));
    mvc.perform(
            post("/api/products/" + productId + "/publish")
                .header("X-User-Id", "7")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.published").value(true));
    JsonNode summary =
        objectMapper
            .readTree(
                mvc.perform(get("/api/products/skus/71"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("data");
    assertTrue(summary.path("productId").isIntegralNumber());
    assertEquals("12.30", summary.path("unitPrice").asText());
    mvc.perform(
            post("/api/products/" + productId + "/unpublish")
                .header("X-User-Id", "7")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.published").value(false));
    mvc.perform(get("/api/products/skus/71")).andExpect(status().isConflict());
    mvc.perform(
            put("/api/products/" + productId)
                .header("X-User-Id", "7")
                .header("X-User-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"updated\",\"price\":\"13.00\",\"skus\":null,\"parameters\":null}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.price").value("13.00"))
        .andExpect(jsonPath("$.data.parameters[0].name").value("material"));
    mvc.perform(get("/api/products/" + productId + "/hot-stat"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sourceType").value("UNKNOWN"));
    assertEquals(
        List.of(0L, 1L, 2L, 3L),
        jdbcTemplate.queryForList(
            "SELECT product_version FROM product_index_outbox ORDER BY product_version",
            Long.class));
  }

  @Test
  void outboxFailureRollsBackNewProductSkuAndParameters() throws Exception {
    jdbcTemplate.execute("RENAME TABLE product_index_outbox TO product_index_outbox_unavailable");
    try {
      mvc.perform(
              post("/api/products")
                  .header("X-User-Id", "7")
                  .header("X-User-Role", "ADMIN")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(productBody()))
          .andExpect(status().isInternalServerError());
      assertEquals(0L, count("product"));
      assertEquals(0L, count("product_sku"));
      assertEquals(0L, count("product_parameter"));
    } finally {
      jdbcTemplate.execute("RENAME TABLE product_index_outbox_unavailable TO product_index_outbox");
    }
  }

  @Test
  void failedUpdateRestoresOriginalFactsVersionAndParameters() throws Exception {
    long productId = createProduct().path("id").asLong();
    jdbcTemplate.execute("RENAME TABLE product_index_outbox TO product_index_outbox_unavailable");
    try {
      mvc.perform(
              put("/api/products/" + productId)
                  .header("X-User-Id", "7")
                  .header("X-User-Role", "ADMIN")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"name\":\"changed\",\"price\":\"99.00\",\"skus\":null,"
                          + "\"parameters\":[{\"name\":\"new\",\"value\":\"new\"}]}"))
          .andExpect(status().isInternalServerError());
      assertEquals(
          "product", jdbcTemplate.queryForObject("SELECT name FROM product", String.class));
      assertEquals(0L, jdbcTemplate.queryForObject("SELECT version FROM product", Long.class));
      assertEquals(
          "material",
          jdbcTemplate.queryForObject("SELECT param_name FROM product_parameter", String.class));
    } finally {
      jdbcTemplate.execute("RENAME TABLE product_index_outbox_unavailable TO product_index_outbox");
    }
  }

  @Test
  void categoryWritesKeepDefaultsAuthenticationAndDisableBehavior() throws Exception {
    mvc.perform(
            post("/api/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"category\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/categories")
                .header("X-User-Id", "7")
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"category\"}"))
        .andExpect(status().isForbidden());
    JsonNode category =
        objectMapper
            .readTree(
                mvc.perform(
                        post("/api/categories")
                            .header("X-User-Id", "7")
                            .header("X-User-Role", "ADMIN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"category\",\"parentId\":null}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.parentId").value(0))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("data");
    long categoryId = category.path("id").asLong();
    mvc.perform(
            put("/api/categories/" + categoryId)
                .header("X-User-Id", "7")
                .header("X-User-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"updated\",\"sortNo\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("updated"));
    mvc.perform(
            delete("/api/categories/" + categoryId)
                .header("X-User-Id", "7")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    mvc.perform(get("/api/categories").param("status", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].status").value(0));
    assertEquals(0L, count("product_index_outbox"));
  }

  @Test
  void activityKeepsTimeMappingAndDatabaseSuccessBeforeRedisFailure() throws Exception {
    OffsetDateTime startAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(1).withNano(0);
    String body =
        "{\"skuId\":71,\"startAt\":\""
            + startAt
            + "\",\"endAt\":\""
            + startAt.plusHours(1)
            + "\",\"stockLimit\":10,\"perUserLimit\":1}";
    JsonNode created =
        objectMapper
            .readTree(
                mvc.perform(
                        post("/api/seckill/activities")
                            .header("X-User-Id", "7")
                            .header("X-User-Role", "ADMIN")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("data");
    long activityId = created.path("activityId").asLong();
    mvc.perform(get("/api/seckill/activities/" + activityId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.remainingStock").value(7));
    assertEquals(startAt, OffsetDateTime.parse(created.path("startAt").asText()));
    mvc.perform(
            put("/api/seckill/activities/" + activityId)
                .header("X-User-Id", "7")
                .header("X-User-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    doThrow(new DataAccessResourceFailureException("Redis unavailable"))
        .when(values)
        .set(anyString(), anyString(), any(java.time.Duration.class));
    mvc.perform(
            post("/api/seckill/activities/" + activityId + "/publish")
                .header("X-User-Id", "7")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isInternalServerError());
    assertEquals(
        "PUBLISHED",
        jdbcTemplate.queryForObject("SELECT status FROM seckill_activity", String.class));
    mvc.perform(
            post("/api/seckill/activities/" + activityId + "/start")
                .header("X-User-Id", "7")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("STARTED"));
  }

  /** 经真实HTTP与事务代理创建商品，并返回其响应数据。 */
  private JsonNode createProduct() throws Exception {
    return objectMapper
        .readTree(
            mvc.perform(
                    post("/api/products")
                        .header("X-User-Id", "7")
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productBody()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .path("data");
  }

  /** 固定商品、SKU和参数载荷，覆盖原请求金额与读取金额的差异。 */
  private String productBody() {
    return "{\"name\":\"product\",\"price\":\"12.3\",\"skus\":[{\"id\":71,\"skuCode\":\"sku-71\","
        + "\"price\":\"12.30\",\"status\":true,\"specJson\":{\"color\":\"black\"}}],"
        + "\"parameters\":[{\"name\":\"material\",\"value\":\"metal\"}]}";
  }

  /** 查询当前独占数据库中固定表的行数。 */
  private long count(String table) {
    if (!ownedTables.contains(table)) throw new IllegalArgumentException("Unknown owned table");
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class);
  }

  /** 使用真实事务、持久化和MVC配置，Redis边界通过替身控制故障。 */
  @Configuration
  @EnableWebMvc
  @EnableTransactionManagement(proxyTargetClass = true)
  @Import({
    ProductController.class,
    ProductQueryServiceImpl.class,
    ProductManagementServiceImpl.class,
    CategoryServiceImpl.class,
    SeckillActivityServiceImpl.class,
    ProductCatalogReader.class,
    ProductValueCodec.class,
    ProductSqlMapper.class,
    ProductIndexOutboxMapper.class,
    ProductIndexOutboxService.class,
    CommonWebConfiguration.class,
    GlobalExceptionHandler.class,
    ProductReadExceptionHandler.class
  })
  static class Application implements WebMvcConfigurer {
    /** 提供仅指向本类数据库的数据源。 */
    @Bean
    public DataSource dataSource() {
      return dataSource;
    }

    /** Mapper与事务共用同一个物理数据源。 */
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource source) {
      return new JdbcTemplate(source);
    }

    /** 提供真实本地事务代理管理器。 */
    @Bean
    public PlatformTransactionManager transactionManager(DataSource source) {
      return new DataSourceTransactionManager(source);
    }

    /** 提供独立的指标注册表。 */
    @Bean
    public MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    /** 提供活动Redis替身。 */
    @Bean
    public StringRedisTemplate redis() {
      return mock(StringRedisTemplate.class);
    }

    /** 沿应用ISO时间格式构造映射器。 */
    @Bean
    public ObjectMapper objectMapper() {
      return new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** MVC绑定与响应使用本次同一个时间格式映射器。 */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
      for (HttpMessageConverter<?> converter : converters) {
        if (converter instanceof MappingJackson2HttpMessageConverter jackson)
          jackson.setObjectMapper(objectMapper());
      }
    }
  }
}
