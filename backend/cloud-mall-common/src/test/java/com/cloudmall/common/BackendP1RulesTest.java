package com.cloudmall.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.Yaml;

/** Regression guards for the frozen backend contracts touched in the P1 pass. */
class BackendP1RulesTest {
  @Test
  void stockChecksDatabaseWritesAndCompensatesRedis() throws Exception {
    String source = source("cloud-mall-stock", "StockController.java");
    String compact = compact(source);
    assertTrue(compact.contains("if(changed!=1)"));
    assertTrue(source.contains("compensateReservation"));
    assertTrue(compact.contains("onduplicatekeyupdate"));
    assertTrue(compact.contains("idempotency_key=values(idempotency_key)"));
    assertTrue(source.contains("SECKILL_ROLLBACK"));
    assertTrue(compact.contains("redis.execute(seckillRollbackScript,keys)"));
  }

  @Test
  void productPersistsAndReadsParameters() throws Exception {
    String source = source("cloud-mall-product", "ProductController.java");
    String compact = compact(source);
    assertTrue(source.contains("product_parameter"));
    assertTrue(source.contains("parameters"));
    assertTrue(source.contains("replaceParameters"));
    assertTrue(compact.contains("db.update(\"deletefromproduct_parameterwhereproduct_id=?\""));
  }

  @Test
  void productMapsDatabaseSnakeCaseToApiDtos() throws Exception {
    String source = source("cloud-mall-product", "ProductController.java");
    String compact = compact(source);
    assertTrue(compact.contains("toActivityResponse(activityRecord(id))"));
    assertTrue(source.contains("new HotStatResponse"));
    assertTrue(compact.contains("publicfinallongactivityId,skuId"));
    assertTrue(compact.contains("publicfinalintremainingStock,perUserLimit"));
    assertTrue(compact.contains("publicfinallongproductId,viewCount,searchCount"));
    assertTrue(compact.contains("publicfinalStringhotScore"));
  }

  @Test
  void paymentAndOrderBindIdempotencyAndPathVariables() throws Exception {
    String payment = source("cloud-mall-pay", "PayController.java");
    String order = source("cloud-mall-order", "OrderController.java");
    assertTrue(payment.contains("payNoFor"));
    assertTrue(payment.contains("Idempotency-Key"));
    assertTrue(order.contains("@PathVariable(\"orderNo\")"));
    assertTrue(order.contains("TIMEOUT_DLQ"));
    assertTrue(order.contains("YearMonth.parse"));
  }

  @Test
  void seckillConsumerRejectsFailuresAndQueueHasDeadLetterRetrySemantics() throws Exception {
    String order = source("cloud-mall-order", "OrderController.java");
    String compact = compact(order);
    String messaging = source("cloud-mall-order", "OrderMessagingConfiguration.java");
    String config =
        Files.readString(
            Path.of("..", "cloud-mall-order", "src", "main", "resources", "application.yml")
                .normalize());
    assertTrue(compact.contains("catch(Exceptione){thrownewIllegalStateException"));
    assertTrue(order.contains("交由 RabbitMQ 重试或死信"));
    assertTrue(messaging.contains("x-dead-letter-exchange"));
    assertTrue(messaging.contains("SECKILL_DLQ"));
    assertTrue(config.contains("default-requeue-rejected: false"));
    assertTrue(config.contains("max-attempts: 3"));
  }

  @Test
  void productEventFailureIsLogged() throws Exception {
    String product = source("cloud-mall-product", "ProductController.java");
    assertTrue(product.contains("LoggerFactory.getLogger(ProductController.class)"));
    assertTrue(product.contains("log.error(\"商品事件发送失败"));
    assertTrue(!product.contains("catch(Exception ignored){}"));
  }

  @Test
  void gatewaySplitsSeckillActivitiesAndOrdersByConcretePath() throws Exception {
    String config =
        Files.readString(
            Path.of("..", "cloud-mall-gateway", "src", "main", "resources", "application.yml")
                .normalize());
    int activities = config.indexOf("id: seckill-activities");
    int orders = config.indexOf("id: order");

    assertTrue(config.contains("server: {port: 8080}"));
    assertTrue(activities >= 0);
    assertTrue(orders > activities);
    assertTrue(
        config.contains(
            "uri: lb://cloud-mall-product, predicates:"
                + " [\"Path=/api/seckill/activities,/api/seckill/activities/**\"]"));
    assertTrue(
        config.contains(
            "uri: lb://cloud-mall-order, predicates:"
                + " [\"Path=/api/orders/**,/api/seckill/orders,/api/seckill/orders/**\"]"));
    assertTrue(!config.contains("Path=/api/seckill/**"));
  }

  @Test
  void gatewayAuthenticationDoesNotTreatSuccessfulDownstreamCompletionAsEmptyAuth()
      throws Exception {
    String filter =
        Files.readString(
            Path.of(
                    "..",
                    "cloud-mall-gateway",
                    "src",
                    "main",
                    "java",
                    "com",
                    "cloudmall",
                    "gateway",
                    "filter",
                    "AuthenticationFilter.java")
                .normalize());
    assertTrue(filter.contains(".switchIfEmpty(Mono.just(\"\"))"));
    assertFalse(filter.contains("switchIfEmpty(unauthorized(exchange))"));
    assertFalse(filter.contains("onErrorResume(error -> unauthorized(exchange))"));
  }

  @Test
  void allBusinessServicesExposePrometheusWithBootManagedRegistry() throws Exception {
    for (String module :
        List.of(
            "cloud-mall-gateway",
            "cloud-mall-user",
            "cloud-mall-product",
            "cloud-mall-cart",
            "cloud-mall-order",
            "cloud-mall-stock",
            "cloud-mall-pay")) {
      String pom = Files.readString(pom(module));
      String config = resource(module);
      assertTrue(
          pom.contains(
              "<groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId>"),
          module + " must declare the Prometheus registry");
      assertTrue(
          config.contains("management.endpoints.web.exposure.include: health,info,prometheus"),
          module + " must expose the Prometheus actuator endpoint");
    }
  }

  @Test
  void startupConfigurationsParseAndKeepLocalInfrastructureEndpoints() throws Exception {
    for (String module :
        List.of(
            "cloud-mall-gateway",
            "cloud-mall-user",
            "cloud-mall-product",
            "cloud-mall-cart",
            "cloud-mall-order",
            "cloud-mall-stock",
            "cloud-mall-pay")) {
      String config = resource(module);
      new Yaml()
          .loadAll(config)
          .iterator()
          .forEachRemaining(document -> assertTrue(document instanceof Map));
      assertTrue(!config.contains("localhost:5672"), module + " still uses the old RabbitMQ port");
    }

    assertTrue(resource("cloud-mall-product").contains("port: 15673"));
    assertTrue(resource("cloud-mall-order").contains("port: 15673"));
    assertTrue(resource("cloud-mall-pay").contains("port: 15673"));
    assertTrue(resource("cloud-mall-stock").contains("port: 15673"));

    String stock = resource("cloud-mall-stock");
    assertTrue(stock.contains("cloudmall_stock"));
    assertTrue(stock.contains("driver-class-name: com.mysql.cj.jdbc.Driver"));
    assertTrue(stock.contains("username: root"));
    assertTrue(stock.contains("password: root"));
  }

  @Test
  void redisClientsUseSpringBoot27RedisProperties() throws Exception {
    for (String module :
        List.of(
            "cloud-mall-gateway",
            "cloud-mall-user",
            "cloud-mall-product",
            "cloud-mall-cart",
            "cloud-mall-stock")) {
      String config = resource(module);
      Map<?, ?> root = (Map<?, ?>) new Yaml().load(config);
      Map<?, ?> spring = mapValue(root, "spring", module);
      Map<?, ?> redis = mapValue(spring, "redis", module);

      assertFalse(spring.containsKey("data.redis"), module + " must not use spring.data.redis");
      assertEquals("127.0.0.1", redis.get("host"), module);
      assertEquals(16379, redis.get("port"), module);
      assertEquals("root", redis.get("password"), module);
      assertEquals("2s", redis.get("timeout"), module);
    }
  }

  @Test
  void seataClientsUseTheLocalFileModeServerAddress() throws Exception {
    for (String module : List.of("cloud-mall-order", "cloud-mall-pay")) {
      Map<?, ?> root = (Map<?, ?>) new Yaml().loadAll(resource(module)).iterator().next();
      Map<?, ?> seata = mapValue(root, "seata", module);
      assertEquals(true, seata.get("enabled"), module);
      assertEquals("file", nestedOrDottedValue(seata, "registry", "type"), module);
      assertEquals("file", nestedOrDottedValue(seata, "config", "type"), module);
      assertEquals(
          "127.0.0.1:8091", nestedOrDottedValue(seata, "service.grouplist", "default"), module);
    }
  }

  @Test
  void backendDocumentsJava17AndSeataJdk21CompatibilityOption() throws Exception {
    String readme = Files.readString(Path.of("..", "README.md").normalize());
    assertTrue(readme.contains("JDK 17"));
    assertTrue(readme.contains("--add-opens java.base/java.lang=ALL-UNNAMED"));
  }

  @Test
  void feignModulesDeclareSpringCloudLoadBalancer() throws Exception {
    for (String module : List.of("cloud-mall-cart", "cloud-mall-order", "cloud-mall-pay")) {
      var document =
          DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom(module).toFile());
      NodeList dependencies = document.getElementsByTagName("dependency");
      boolean found = false;
      for (int i = 0; i < dependencies.getLength(); i++) {
        Element dependency = (Element) dependencies.item(i);
        if ("spring-cloud-starter-loadbalancer".equals(text(dependency, "artifactId"))) {
          found = "org.springframework.cloud".equals(text(dependency, "groupId"));
          break;
        }
      }
      assertTrue(found, module + " must declare Spring Cloud LoadBalancer");
    }
  }

  @Test
  void gatewayDeclaresSpringCloudLoadBalancer() throws Exception {
    var document =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(pom("cloud-mall-gateway").toFile());
    NodeList dependencies = document.getElementsByTagName("dependency");
    boolean found = false;
    for (int i = 0; i < dependencies.getLength(); i++) {
      Element dependency = (Element) dependencies.item(i);
      if ("spring-cloud-starter-loadbalancer".equals(text(dependency, "artifactId"))) {
        found = "org.springframework.cloud".equals(text(dependency, "groupId"));
        break;
      }
    }
    assertTrue(found, "cloud-mall-gateway must declare Spring Cloud LoadBalancer");
  }

  @Test
  void userAndProductDeclareNacosDiscovery() throws Exception {
    for (String module : List.of("cloud-mall-user", "cloud-mall-product")) {
      var document =
          DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom(module).toFile());
      NodeList dependencies = document.getElementsByTagName("dependency");
      boolean found = false;
      for (int i = 0; i < dependencies.getLength(); i++) {
        Element dependency = (Element) dependencies.item(i);
        if ("spring-cloud-starter-alibaba-nacos-discovery".equals(text(dependency, "artifactId"))) {
          found = "com.alibaba.cloud".equals(text(dependency, "groupId"));
          break;
        }
      }
      assertTrue(found, module + " must declare Nacos Discovery");
    }
  }

  @Test
  void missingOrderIdempotencyHeaderMapsToInvalidArgument() throws Exception {
    String handler =
        Files.readString(
            Path.of(
                    "..",
                    "cloud-mall-common",
                    "src",
                    "main",
                    "java",
                    "com",
                    "cloudmall",
                    "common",
                    "web",
                    "GlobalExceptionHandler.java")
                .normalize());
    String order = source("cloud-mall-order", "OrderController.java");
    assertTrue(handler.contains("MissingRequestHeaderException"));
    assertTrue(handler.contains("COMMON_INVALID_ARGUMENT"));
    assertTrue(order.contains("@RequestHeader(\"Idempotency-Key\")"));
    assertTrue(compact(order).contains("key==null||key.isBlank()"));
  }

  private static String resource(String module) throws Exception {
    return Files.readString(
        Path.of("..", module, "src", "main", "resources", "application.yml").normalize());
  }

  private static Path pom(String module) {
    return Path.of("..", module, "pom.xml").normalize();
  }

  private static String text(Element element, String tagName) {
    return element.getElementsByTagName(tagName).item(0).getTextContent();
  }

  private static Map<?, ?> mapValue(Map<?, ?> map, String key, String module) {
    Object value = map.get(key);
    assertTrue(value instanceof Map, module + " must define spring." + key);
    return (Map<?, ?>) value;
  }

  private static Object nestedOrDottedValue(Map<?, ?> map, String parentKey, String childKey) {
    Object dottedValue = map.get(parentKey + "." + childKey);
    if (dottedValue != null) {
      return dottedValue;
    }
    Object parent = map;
    for (String segment : parentKey.split("\\.")) {
      parent = ((Map<?, ?>) parent).get(segment);
    }
    return ((Map<?, ?>) parent).get(childKey);
  }

  private static String source(String module, String file) throws Exception {
    Path sourceRoot = Path.of("..", module, "src", "main", "java").normalize();
    try (var paths = Files.walk(sourceRoot)) {
      Path sourcePath =
          paths
              .filter(path -> path.getFileName().toString().equals(file))
              .findFirst()
              .orElseThrow();
      return Files.readString(sourcePath);
    }
  }

  private static String compact(String source) {
    return source.replaceAll("\\s+", "");
  }
}
