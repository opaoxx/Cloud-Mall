package com.cloudmall.pay;

import static com.cloudmall.pay.PayProtocolMetricsTest.count;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.pay.controller.PayController;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.impl.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.seata.core.context.RootContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.servlet.ServletManagementContextAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ClassUtils;

/** 同进程真实Web/Actuator/Prometheus；只显式导入Web指标配置，业务依赖不联网。 */
class PayProtocolMetricsEndpointTest {
  /** 同时隔离显式注册和PayApplication扫描，禁止泄漏进Delivery子JVM。 */
  private static final String PROFILE = "cm18-protocol-metrics-endpoint-test";

  @Test
  void originalMethodsReachTheRealHttpScrapeThroughInjectedProductionMetrics() throws Exception {
    Instant started = Instant.now();
    String runId = started.toEpochMilli() + "-" + UUID.randomUUID();
    Path evidence =
        Path.of(System.getProperty("basedir", "."), "target", "cm18-protocol-metrics", runId);
    Files.createDirectories(evidence);
    write(
        evidence.resolve("started.json"),
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "testClass",
                    getClass().getName(),
                    "runId",
                    runId,
                    "startedAt",
                    started.toString())));
    System.out.println("CM18_PROTOCOL_METRICS_EVIDENCE runId=" + runId + " startedAt=" + started);
    PayProtocolMetricsTest.Fixture fixture = new PayProtocolMetricsTest.Fixture();
    CountDownLatch closed = new CountDownLatch(1);
    ExecutorService httpExecutor = Executors.newSingleThreadExecutor();
    ServletWebServerApplicationContext context = null;
    boolean successful = false;
    try {
      // 1. 不扫描PayApplication，不导入数据源、MQ、Nacos、Seata或调度自动配置。
      context =
          (ServletWebServerApplicationContext)
              new SpringApplicationBuilder(EndpointConfiguration.class)
                  .web(WebApplicationType.SERVLET)
                  .profiles(PROFILE)
                  .properties(
                      Map.ofEntries(
                          Map.entry("server.address", "127.0.0.1"),
                          Map.entry("server.port", "0"),
                          Map.entry("spring.main.allow-bean-definition-overriding", "false"),
                          Map.entry("spring.cloud.bootstrap.enabled", "false"),
                          Map.entry("spring.cloud.discovery.enabled", "false"),
                          Map.entry("spring.cloud.nacos.discovery.enabled", "false"),
                          Map.entry("spring.cloud.nacos.discovery.register-enabled", "false"),
                          Map.entry("spring.cloud.nacos.config.enabled", "false"),
                          Map.entry("spring.cloud.sentinel.enabled", "false"),
                          Map.entry("seata.enabled", "false"),
                          Map.entry("spring.task.scheduling.enabled", "false"),
                          Map.entry("management.endpoints.web.exposure.include", "prometheus"),
                          Map.entry(
                              "management.metrics.export.prometheus.pushgateway.enabled", "false"),
                          Map.entry("cloudmall.internal.callback-token", "test-token")))
                  .initializers(
                      application -> {
                        application.addApplicationListener(
                            event -> {
                              if (event instanceof ContextClosedEvent) closed.countDown();
                            });
                        var beans = application.getBeanFactory();
                        beans.registerSingleton("controlledFixture", fixture);
                        beans.registerSingleton("orderClient", fixture.orders);
                        beans.registerSingleton("userClient", fixture.users);
                        beans.registerSingleton("payRecordMapper", fixture.records);
                        beans.registerSingleton("payCallbackLogMapper", fixture.logs);
                        beans.registerSingleton("payDebitIntentMapper", fixture.intents);
                        beans.registerSingleton("legacyFailureService", fixture.legacyFailure);
                        beans.registerSingleton("tccPaymentService", fixture.publicTcc);
                        beans.registerSingleton("debitModeConfiguration", fixture.mode);
                      })
                  .run("--spring.config.name=cm18-protocol-metrics-endpoint-test");
      PayProtocolMetrics metrics = context.getBean(PayProtocolMetrics.class);
      PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
      assertSame(registry, ReflectionTestUtils.getField(metrics, "registry"));
      PayController controller = context.getBean(PayController.class);
      assertSame(fixture.controller, controller);
      for (Object service :
          List.of(
              controller,
              context.getBean(PayConsistencyService.class),
              context.getBean(PayTccCoordinatorServiceImpl.class),
              context.getBean(PayTccExecutionEvidence.class),
              context.getBean(PayTccDeliveryServiceImpl.class),
              context.getBean(PayCallbackReceiptService.class)))
        assertSame(metrics, ReflectionTestUtils.getField(service, "metrics"));
      assertNotSame(PayProtocolMetrics.noop(), metrics);
      assertFalse(
          context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
      assertTrue(context.getBeansOfType(javax.sql.DataSource.class).isEmpty());
      assertTrue(
          context
              .getBeansOfType(org.springframework.amqp.rabbit.connection.ConnectionFactory.class)
              .isEmpty());
      String redisConnectionType =
          "org.springframework.data.redis.connection.RedisConnectionFactory";
      boolean redisTypePresent =
          ClassUtils.isPresent(redisConnectionType, context.getClassLoader());
      if (redisTypePresent) {
        Class<?> redisType = ClassUtils.forName(redisConnectionType, context.getClassLoader());
        assertTrue(context.getBeansOfType(redisType).isEmpty());
      } else {
        assertFalse(redisTypePresent);
      }
      assertEquals("127.0.0.1", context.getEnvironment().getProperty("server.address"));
      int port = context.getWebServer().getPort();
      assertTrue(port > 0);
      HttpClient client =
          HttpClient.newBuilder()
              .executor(httpExecutor)
              .connectTimeout(Duration.ofSeconds(5))
              .build();
      URI endpoint = URI.create("http://127.0.0.1:" + port + "/actuator/prometheus");
      String before = scrape(client, endpoint);
      write(evidence.resolve("before.prom"), before);
      assertEquals(
          0,
          sample(
              before,
              "cloudmall_pay_protocol_route_total",
              Map.of("mode", "TCC", "entry", "MOCK_SUCCESS")));
      for (String family :
          List.of("protocol_route", "protocol_observation", "tcc_decision_rpc", "callback_claim"))
        assertFalse(before.contains("# TYPE cloudmall_pay_" + family + "_total counter"));
      // 2. 每个family必须来自容器内真实原方法，绝不直接调用metrics记录API。
      AuthContext.set(7L, "USER");
      try {
        fixture.payment.debitMode = "TCC";
        controller.success(PayProtocolMetricsTest.Fixture.PAY);
        controller.success(PayProtocolMetricsTest.Fixture.PAY);
        controller.callback("test-token", fixture.callback(true));
        fixture.payment.debitMode = "LEGACY";
        when(fixture.intents.findByPayNo(PayProtocolMetricsTest.Fixture.PAY))
            .thenReturn(fixture.legacyIntent);
        assertEquals(
            "SUCCESS",
            context
                .getBean(PayConsistencyService.class)
                .process(PayProtocolMetricsTest.Fixture.PAY));
        when(fixture.intents.findByPayNo(PayProtocolMetricsTest.Fixture.PAY)).thenReturn(null);
        fixture.execution.xid = null;
        assertEquals(
            "COMMITTED",
            context.getBean(PayTccCoordinatorServiceImpl.class).execute(fixture.dispatch()).state);
        fixture.job.state = "REFUNDED";
        assertEquals(
            "REFUNDED",
            context
                .getBean(PayTccDeliveryServiceImpl.class)
                .deliver(PayProtocolMetricsTest.Fixture.PAY)
                .state);
      } finally {
        AuthContext.clear();
        RootContext.unbind();
      }
      String after = scrape(client, endpoint);
      write(evidence.resolve("after.prom"), after);
      // 3. 实际HTTP原文验证增量、有限标签和资金/交付维度，不把RETURNED当资金成功。
      assertEquals(
          2,
          sample(
              after,
              "cloudmall_pay_protocol_route_total",
              Map.of("mode", "TCC", "entry", "MOCK_SUCCESS")));
      assertEquals(
          1,
          sample(
              after,
              "cloudmall_pay_protocol_route_total",
              Map.of("mode", "TCC", "entry", "CALLBACK")));
      assertEquals(
          1,
          sample(
              after,
              "cloudmall_pay_callback_claim_total",
              Map.of("mode", "TCC", "direction", "SUCCESS", "result", "NEW")));
      assertEquals(
          1,
          sample(
              after,
              "cloudmall_pay_tcc_decision_rpc_total",
              Map.of("origin", "INITIAL", "decision", "COMMIT", "result", "RETURNED")));
      assertEquals(
          1,
          sample(
              after,
              "cloudmall_pay_protocol_observation_total",
              Map.of("mode", "TCC", "scope", "TCC_EXECUTE", "outcome", "MONEY_COMMITTED")));
      assertEquals(
          1,
          sample(
              after,
              "cloudmall_pay_protocol_observation_total",
              Map.of("mode", "TCC", "scope", "TCC_DELIVERY", "outcome", "REFUNDED")));
      assertEquals(
          1,
          sample(
              after,
              "cloudmall_pay_protocol_observation_total",
              Map.of("mode", "LEGACY", "scope", "LEGACY_RECOVERY", "outcome", "DELIVERED")));
      for (String family :
          List.of("protocol_route", "protocol_observation", "tcc_decision_rpc", "callback_claim"))
        assertTrue(after.contains("# TYPE cloudmall_pay_" + family + "_total counter"));
      List<Map<String, Object>> projection = new ArrayList<>();
      registry.getMeters().stream()
          .filter(meter -> meter.getId().getName().startsWith("cloudmall.pay."))
          .forEach(
              meter -> {
                Map<String, String> tags = new TreeMap<>();
                meter.getId().getTags().forEach(tag -> tags.put(tag.getKey(), tag.getValue()));
                validateTags(meter.getId().getName(), tags);
                projection.add(
                    Map.of(
                        "name",
                        meter.getId().getName(),
                        "tags",
                        tags,
                        "count",
                        count(
                            registry,
                            meter.getId().getName(),
                            tags.entrySet().stream()
                                .flatMap(
                                    entry ->
                                        java.util.stream.Stream.of(
                                            entry.getKey(), entry.getValue()))
                                .toArray(String[]::new))));
              });
      for (String privateValue :
          List.of(
              PayProtocolMetricsTest.Fixture.PAY,
              PayProtocolMetricsTest.Fixture.XID,
              PayProtocolMetricsTest.Fixture.CALLBACK,
              fixture.payment.orderNo,
              "10.00",
              "reason=",
              "sha256=",
              "payNo=")) assertFalse(after.contains(privateValue));
      write(
          evidence.resolve("projection.json"),
          new ObjectMapper()
              .writeValueAsString(
                  Map.of(
                      "runId",
                      runId,
                      "startedAt",
                      started.toString(),
                      "scrapedAt",
                      Instant.now().toString(),
                      "meters",
                      projection,
                      "realRegistryInjected",
                      true,
                      "controllerInheritedInjection",
                      true,
                      "businessDependenciesAreMocks",
                      true,
                      "redisConnectionTypeObservation",
                      redisTypePresent ? "PRESENT_WITH_ZERO_BEANS" : "TYPE_ABSENT")));
      verifyNoInteractions(fixture.orders, fixture.users, fixture.userTcc);
      successful = true;
    } finally {
      AuthContext.clear();
      RootContext.unbind();
      // 独立finally保证即使close失败也关闭并有界等待测试自有HTTP executor。
      try {
        if (context != null) {
          context.close();
          assertTrue(closed.await(10, TimeUnit.SECONDS));
          assertFalse(context.isActive());
        }
      } finally {
        httpExecutor.shutdownNow();
        assertTrue(httpExecutor.awaitTermination(10, TimeUnit.SECONDS));
        write(
            evidence.resolve("finished.json"),
            new ObjectMapper()
                .writeValueAsString(
                    Map.of(
                        "runId",
                        runId,
                        "startedAt",
                        started.toString(),
                        "finishedAt",
                        Instant.now().toString(),
                        "assertionsCompleted",
                        successful,
                        "httpExecutorTerminated",
                        httpExecutor.isTerminated())));
        System.out.println(
            "CM18_PROTOCOL_METRICS_EVIDENCE runId=" + runId + " finishedAt=" + Instant.now());
      }
    }
  }

  @Test
  void endpointConfigurationIsExplicitlyProfileIsolated() {
    assertNotNull(EndpointConfiguration.class.getAnnotation(TestConfiguration.class));
    assertArrayEquals(
        new String[] {PROFILE}, EndpointConfiguration.class.getAnnotation(Profile.class).value());
    assertNull(
        EndpointConfiguration.class.getAnnotation(
            org.springframework.boot.SpringBootConfiguration.class));
    // 没有主配置/组件扫描/EnableAutoConfiguration；白名单导入也由同一profile约束。
    assertNull(
        EndpointConfiguration.class.getAnnotation(
            org.springframework.context.annotation.ComponentScan.class));
    assertNull(
        EndpointConfiguration.class.getAnnotation(
            org.springframework.boot.autoconfigure.EnableAutoConfiguration.class));
  }

  /** 真实HTTP读取，业务请求从不通过此客户端发出。 */
  private static String scrape(HttpClient client, URI endpoint) throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode());
    return response.body();
  }

  /** 不依赖Prometheus标签顺序，解析原HTTP返回中的指定有限样本。 */
  private static double sample(String scrape, String name, Map<String, String> expected) {
    for (String line : scrape.split("\\R")) {
      if (!line.startsWith(name + "{")) continue;
      int end = line.indexOf('}');
      Map<String, String> tags = new HashMap<>();
      Matcher matcher = Pattern.compile("([a-z_]+)=\"([^\"]*)\"").matcher(line.substring(0, end));
      while (matcher.find()) tags.put(matcher.group(1), matcher.group(2));
      if (tags.equals(expected)) return Double.parseDouble(line.substring(end + 1).trim());
    }
    return 0;
  }

  /** 对实际registry中每个业务metric精确核标签键与对应enum值。 */
  private static void validateTags(String name, Map<String, String> tags) {
    Map<String, Class<? extends Enum<?>>> allowed =
        switch (name) {
          case "cloudmall.pay.protocol.route" ->
              Map.of(
                  "mode", PayProtocolMetrics.Mode.class, "entry", PayProtocolMetrics.Entry.class);
          case "cloudmall.pay.protocol.observation" ->
              Map.of(
                  "mode",
                  PayProtocolMetrics.Mode.class,
                  "scope",
                  PayProtocolMetrics.Scope.class,
                  "outcome",
                  PayProtocolMetrics.Outcome.class);
          case "cloudmall.pay.tcc.decision.rpc" ->
              Map.of(
                  "origin",
                  PayProtocolMetrics.Origin.class,
                  "decision",
                  PayProtocolMetrics.Decision.class,
                  "result",
                  PayProtocolMetrics.RpcResult.class);
          case "cloudmall.pay.callback.claim" ->
              Map.of(
                  "mode",
                  PayProtocolMetrics.Mode.class,
                  "direction",
                  PayProtocolMetrics.Direction.class,
                  "result",
                  PayProtocolMetrics.ClaimResult.class);
          default -> throw new AssertionError("unexpected metric family");
        };
    assertEquals(allowed.keySet(), tags.keySet());
    tags.forEach(
        (key, value) ->
            assertTrue(
                Arrays.stream(allowed.get(key).getEnumConstants())
                    .anyMatch(constant -> constant.name().equals(value))));
    if (name.equals("cloudmall.pay.protocol.route")) assertNotEquals("UNKNOWN", tags.get("mode"));
  }

  /** 每次测试的唯一标准target目录保留原文，不覆盖首红或第二轮package证据。 */
  private static void write(Path path, String value) throws Exception {
    Files.writeString(path, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
  }

  /** 白名单仅包含Web/Actuator/Prometheus，双注解阻止正常应用扫描意外装配。 */
  @TestConfiguration(proxyBeanMethods = false)
  @Profile(PROFILE)
  @Import(PayProtocolMetrics.class)
  @ImportAutoConfiguration({
    PropertyPlaceholderAutoConfiguration.class,
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    JacksonAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    WebMvcAutoConfiguration.class,
    EndpointAutoConfiguration.class,
    WebEndpointAutoConfiguration.class,
    ServletManagementContextAutoConfiguration.class,
    ManagementContextAutoConfiguration.class,
    MetricsAutoConfiguration.class,
    PrometheusMetricsExportAutoConfiguration.class
  })
  static class EndpointConfiguration {
    /** 真实HTTP Bean，继承的metrics字段由Spring注入。 */
    @Bean
    PayController controller(PayProtocolMetricsTest.Fixture fixture) {
      return fixture.controller;
    }

    /** 原Legacy service，不启用调度器。 */
    @Bean
    PayConsistencyService consistency(PayProtocolMetricsTest.Fixture fixture) {
      return fixture.legacy;
    }

    /** 原Coordinator。 */
    @Bean
    PayTccCoordinatorServiceImpl coordinator(PayProtocolMetricsTest.Fixture fixture) {
      return fixture.coordinator;
    }

    /** 原共享证据方法。 */
    @Bean
    PayTccExecutionEvidence evidence(PayProtocolMetricsTest.Fixture fixture) {
      return fixture.evidence;
    }

    /** 原交付方法。 */
    @Bean
    PayTccDeliveryServiceImpl delivery(PayProtocolMetricsTest.Fixture fixture) {
      return fixture.delivery;
    }

    /** 原回调绑定方法。 */
    @Bean
    PayCallbackReceiptService callbackReceipts(PayProtocolMetricsTest.Fixture fixture) {
      return fixture.callbackReceipt;
    }
  }
}
