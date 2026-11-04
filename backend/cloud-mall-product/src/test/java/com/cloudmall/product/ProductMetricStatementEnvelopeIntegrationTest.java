package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.domain.po.ProductOnlineStatPO;
import com.cloudmall.product.mapper.ProductMetricMapper;
import com.cloudmall.product.service.impl.index.ProductIndexDocumentLoader;
import com.cloudmall.product.service.impl.metrics.ProductMetricService;
import com.cloudmall.product.util.ProductMetricEvents;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcProperties;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.SQLWarningException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Real Spring JDBC, production Mapper/Service and local transaction proxy over controlled leaves.
 */
class ProductMetricStatementEnvelopeIntegrationTest {
  /** Synthetic owner is never used to create an external resource. */
  private static final String OWNER = "0123456789abcdef0123456789abcdef";

  /** The exact production UPDATE; no normalization is used in assertions. */
  private static final String STAT_SQL =
      "UPDATE product_online_stat SET"
          + " view_count=?,search_count=?,sold_count=?,hot_score=?,metric_version=?,last_event_id=?,updated_at=?"
          + " WHERE product_id=?";

  /** Projection row counts are intentionally ignored by the original Mapper. */
  private static final String PROJECTION_SQL =
      "INSERT INTO"
          + " product_metric_projection_outbox(product_id,metric_version,created_at,updated_at,next_retry_at)"
          + " VALUES(?,?,?,?,?)";

  /** Fixed cold codec does not run in a business invocation. */
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void bootFactoryOffAndOnMatchColdConfigurationAndOriginalDataSource() throws Exception {
    // 1. The actual conditional configuration sees a genuine partially constructed fixture.
    try (PropertiesScope properties = new PropertiesScope()) {
      List<String> actors = new ArrayList<>();
      ProductMetricQueryInfoCacheSupport.ACTOR_HOOK.set(
          (kind, admission) -> {
            actors.add(kind);
            throw new StopBeforeActor();
          });
      try {
        for (String invalid : List.of("", "TRUE", " true", "false ")) {
          System.setProperty(ProductMetricStatementEnvelopeSupport.FLAG, invalid);
          assertThrows(
              IllegalStateException.class,
              () -> ProductOnlineMetricsPipelineIT.Single10.main(new String[] {"batch1"}));
          assertThrows(
              IllegalStateException.class,
              () -> new ProductOnlineMetricsPipelineIT.Pipeline("load", 300));
          assertTrue(actors.isEmpty());
        }
        System.setProperty(ProductMetricStatementEnvelopeSupport.FLAG, "true");
        assertThrows(
            IllegalStateException.class,
            () -> new ProductOnlineMetricsPipelineIT.Pipeline("load", 300));
        System.setProperty("cloudmall.cm21.single10.callback-service-observation", "false");
        assertThrows(
            IllegalStateException.class,
            () -> ProductOnlineMetricsPipelineIT.Single10.main(new String[] {"batch1"}));
        assertTrue(actors.isEmpty());
      } finally {
        ProductMetricQueryInfoCacheSupport.ACTOR_HOOK.remove();
        System.setProperty("cloudmall.cm21.single10.callback-service-observation", "true");
      }
      for (String timeout : new String[] {null, "7s", "4294967296s", "2147483648s"}) {
        JdbcTemplate[] pair = new JdbcTemplate[2];
        for (int selected = 0; selected < 2; selected++) {
          boolean enabled = selected == 1;
          var run = partialPipeline(enabled, enabled);
          Leaf leaf = new Leaf();
          int slot = selected;
          ApplicationContextRunner runner =
              new ApplicationContextRunner()
                  .withInitializer(
                      context -> context.getEnvironment().setActiveProfiles("cm21-pipeline"))
                  .withConfiguration(AutoConfigurations.of(JdbcTemplateAutoConfiguration.class))
                  .withUserConfiguration(ColdBeans.class)
                  .withBean(DataSource.class, () -> leaf.source)
                  .withPropertyValues(
                      "spring.jdbc.template.fetch-size=17", "spring.jdbc.template.max-rows=23");
          if (timeout != null)
            runner = runner.withPropertyValues("spring.jdbc.template.query-timeout=" + timeout);
          current().set(run);
          try {
            runner.run(
                context -> {
                  assertNull(context.getStartupFailure());
                  assertEquals(1, context.getBeansOfType(JdbcOperations.class).size());
                  assertEquals(
                      enabled ? 1 : 0,
                      context
                          .getBeansOfType(
                              ProductOnlineMetricsPipelineIT.StatementEnvelopeConfiguration.class)
                          .size(),
                      () ->
                          org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport
                              .get(context.getBeanFactory())
                              .getConditionAndOutcomesBySource()
                              .toString());
                  assertTrue(
                      context.getBeanFactory().getBeanDefinition("jdbcTemplate").isPrimary());
                  JdbcTemplate template = context.getBean("jdbcTemplate", JdbcTemplate.class);
                  pair[slot] = template;
                  assertEquals(
                      enabled ? ProductMetricStatementEnvelopeSupport.class : JdbcTemplate.class,
                      enabled ? template.getClass().getEnclosingClass() : template.getClass());
                  assertSame(leaf.source, template.getDataSource());
                  assertEquals(17, template.getFetchSize());
                  assertEquals(23, template.getMaxRows());
                  assertNull(ReflectionTestUtils.getField(template, "exceptionTranslator"));
                  assertSame(
                      template,
                      ReflectionTestUtils.getField(
                          context.getBean(ProductMetricMapper.class), "jdbc"));
                  for (String field : List.of("admission", "projection")) {
                    JdbcTemplate independent =
                        (JdbcTemplate)
                            ReflectionTestUtils.getField(
                                context.getBean(ProductMetricMapper.class), field);
                    assertEquals(JdbcTemplate.class, independent.getClass());
                    assertSame(leaf.source, independent.getDataSource());
                    assertEquals(field.equals("admission") ? 1 : 5, independent.getQueryTimeout());
                  }
                  assertSame(
                      leaf.source,
                      context.getBean(DataSourceTransactionManager.class).getDataSource());
                  if (enabled)
                    assertTrue(
                        run.statementEnvelope.verifyRuntime(
                            context, context.getBean(ProductMetricMapper.class), leaf.source));
                  assertNull(ReflectionTestUtils.getField(template, "exceptionTranslator"));
                  verify(leaf.source, never()).getConnection();
                  verify(leaf.source, never()).getConnection(anyString(), anyString());
                });
          } finally {
            current().remove();
            restoreObserverInputs();
          }
        }
        // 2. Cold fields and logger booleans are read without initializing a translator.
        assertEquals(pair[0].getQueryTimeout(), pair[1].getQueryTimeout());
        assertEquals(pair[0].isIgnoreWarnings(), pair[1].isIgnoreWarnings());
        assertEquals(loggerVector(pair[0]), loggerVector(pair[1]));
        for (boolean ignore : new boolean[] {true, false}) {
          Leaf original = new Leaf(), observed = new Leaf();
          JdbcTemplate plain = new JdbcTemplate(original.source);
          JdbcTemplate enhanced =
              ProductMetricStatementEnvelopeSupport.createTemplate(
                  observed.source, new JdbcProperties(), collector(() -> 0L));
          plain.setIgnoreWarnings(ignore);
          enhanced.setIgnoreWarnings(ignore);
          if (ignore) {
            assertEquals(1, plain.update("UPDATE warning_leaf SET value=?", new Object[] {1}));
            assertEquals(1, enhanced.update("UPDATE warning_leaf SET value=?", new Object[] {1}));
          } else {
            SQLWarningException first =
                assertThrows(
                    SQLWarningException.class,
                    () -> plain.update("UPDATE warning_leaf SET value=?", new Object[] {1}));
            SQLWarningException second =
                assertThrows(
                    SQLWarningException.class,
                    () -> enhanced.update("UPDATE warning_leaf SET value=?", new Object[] {1}));
            assertSame(original.warning, first.getSQLWarning());
            assertSame(observed.warning, second.getSQLWarning());
          }
          assertEquals(original.warningReads, observed.warningReads);
          verify(original.connection).close();
          verify(observed.connection).close();
          assertEquals(1, original.closedStatements);
          assertEquals(1, observed.closedStatements);
        }
      }
      // 3. A custom JdbcOperations makes both Boot and the enabled observer factory back off.
      var run = partialPipeline(true);
      JdbcOperations custom = mock(JdbcOperations.class);
      current().set(run);
      try {
        new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("cm21-pipeline"))
            .withConfiguration(AutoConfigurations.of(JdbcTemplateAutoConfiguration.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean("customOperations", JdbcOperations.class, () -> custom)
            .withUserConfiguration(ColdFactoryImport.class)
            .run(
                context -> {
                  assertNull(context.getStartupFailure());
                  assertEquals(
                      Map.of("customOperations", custom),
                      context.getBeansOfType(JdbcOperations.class));
                  assertFalse(context.containsBean("jdbcTemplate"));
                });
      } finally {
        current().remove();
      }
    }
  }

  @Test
  void actualMapperNormalPairUsesOriginalJdbcTemplateOverloadOnce() throws Exception {
    for (int projectionRows : new int[] {0, 1, 2}) {
      try (Harness harness = new Harness(null)) {
        harness.leaf.projectionRows = projectionRows;
        LocalDateTime time = LocalDateTime.of(2026, 10, 31, 12, 0);
        var serviceScope =
            harness.timing.serviceEnter(ProductMetricPipelineLoadSupport.Stage.SETUP, "apply", 1);
        try {
          harness.mapper.updateStat(stat(), null, time);
        } finally {
          harness.timing.serviceExit(
              serviceScope, false, ProductMetricPipelineLoadSupport.Stage.SETUP);
        }
        assertEquals(List.of(STAT_SQL, PROJECTION_SQL), harness.leaf.updates);
        assertEquals(
            List.of(3L, 4L, 5L, 57L, 6L, NullValue.INSTANCE, time, 41L),
            harness.leaf.boundValues.get(0));
        assertEquals(List.of(41L, 6L, time, time, time), harness.leaf.boundValues.get(1));
        assertEquals(1, harness.state().path("normalFullPairs").asLong());
        assertEquals(2, harness.leaf.closedStatements);
        Object first = new Object();
        Object[] arguments = {first, null};
        String sql = new String("UPDATE arbitrary_leaf SET first=?,second=?");
        assertEquals(1, harness.template.update(sql, arguments));
        assertSame(sql, harness.leaf.preparedSql.get(harness.leaf.preparedSql.size() - 1));
        assertSame(first, harness.leaf.boundValues.get(2).get(0));
        assertSame(first, arguments[0]);
        assertNull(arguments[1]);
        harness.assertQuiescent();
      }
    }
  }

  @Test
  void statRowGateAndSqlFaultsPreserveOriginalCauseAndDmlOrder() throws Exception {
    for (int rows : new int[] {0, 2}) {
      try (Harness harness = new Harness(null)) {
        harness.leaf.statRows = rows;
        IllegalStateException lost =
            assertThrows(
                IllegalStateException.class, () -> harness.service.applyBatch(harness.events));
        assertEquals("METRIC_STAT_LOST", lost.getMessage());
        assertEquals(1, harness.leaf.roleCount(STAT_SQL));
        assertEquals(0, harness.leaf.roleCount(PROJECTION_SQL));
        verify(harness.leaf.connection).rollback();
        verify(harness.leaf.connection, never()).commit();
        assertEquals(1, harness.state().path("partialFrames").asLong());
        harness.assertQuiescent();
      }
    }
    try (Harness harness = new Harness(null)) {
      SQLException original =
          new SQLException("controlled stat", "40001", 1213, new IllegalArgumentException("cause"));
      original.addSuppressed(new IllegalStateException("existing suppressed"));
      Throwable[] suppressed = original.getSuppressed().clone();
      harness.leaf.statFailure = original;
      assertSame(
          harness.translation,
          assertThrows(
              DataAccessResourceFailureException.class,
              () -> harness.service.applyBatch(harness.events)));
      assertSame(original, harness.translation.getCause());
      assertArrayEquals(suppressed, original.getSuppressed());
      assertEquals(0, harness.leaf.roleCount(PROJECTION_SQL));
      assertEquals(1, harness.leaf.roleCount(STAT_SQL));
      verify(harness.leaf.connection).rollback();
      assertFalse(harness.timing.hasFailed());
      harness.assertQuiescent();
    }
  }

  @Test
  void projectionFaultAndLaterEventFaultKeepOriginalFailurePriority() throws Exception {
    IllegalStateException early = new IllegalStateException("original projection failure");
    IllegalArgumentException late = new IllegalArgumentException("original later ledger failure");
    try (Harness harness = new Harness(null)) {
      harness.leaf.projectionFailure = early;
      harness.leaf.secondLedgerFailure = late;
      assertSame(
          early,
          assertThrows(
              IllegalStateException.class, () -> harness.service.applyBatch(harness.events)));
      assertEquals(1, harness.leaf.ledgerCalls);
      assertEquals(1, harness.leaf.roleCount(PROJECTION_SQL));
      assertEquals(1, harness.state().path("completeThrownAttempts").asLong());
      assertEquals(0, harness.state().path("normalFullPairs").asLong());
      verify(harness.leaf.connection).rollback();
      harness.assertQuiescent();
    }
    try (Harness harness = new Harness(null)) {
      harness.leaf.secondLedgerFailure = late;
      assertSame(
          late,
          assertThrows(
              IllegalArgumentException.class, () -> harness.service.applyBatch(harness.events)));
      assertEquals(2, harness.leaf.ledgerCalls);
      assertEquals(1, harness.leaf.roleCount(STAT_SQL));
      assertEquals(1, harness.leaf.roleCount(PROJECTION_SQL));
      assertEquals(1, harness.state().path("normalFullPairs").asLong());
      verify(harness.leaf.connection).rollback();
      harness.assertQuiescent();
    }
  }

  @Test
  void observerFaultInjectionKeepsReturnThrowableCauseAndSuppressedIdentity() throws Exception {
    for (String point :
        List.of(
            "beforeMapperProceed",
            "beforeTemplateCall",
            "afterTemplateCall",
            "afterMapperProceed",
            "closeMapperScope",
            "commit")) {
      for (boolean businessThrows : new boolean[] {false, true}) {
        try (Harness harness = new Harness(null)) {
          harness.observation.setObserverHook(
              boundary -> {
                if (point.equals(boundary)) throw new AssertionError("private observer failure");
              });
          IllegalStateException original =
              new IllegalStateException(
                  "business primary", new IllegalArgumentException("original cause"));
          original.addSuppressed(new IllegalArgumentException("original suppressed"));
          Throwable cause = original.getCause();
          Throwable[] suppressed = original.getSuppressed().clone();
          StackTraceElement[] stack = original.getStackTrace().clone();
          if (businessThrows) {
            harness.leaf.projectionFailure = original;
            assertSame(
                original,
                assertThrows(
                    IllegalStateException.class, () -> harness.service.applyBatch(harness.events)));
            verify(harness.leaf.connection).rollback();
          } else {
            assertEquals(2, harness.service.applyBatch(harness.events).appliedCount);
            verify(harness.leaf.connection).commit();
          }
          assertSame(cause, original.getCause());
          assertArrayEquals(suppressed, original.getSuppressed());
          assertArrayEquals(stack, original.getStackTrace());
          assertFalse(harness.timing.hasFailed());
          assertNotEquals("NONE", harness.state().path("failureCode").asText());
          harness.assertQuiescent();
        }
      }
    }
    try (Harness harness =
        new Harness(
            () -> {
              throw new AssertionError("clock leaf");
            })) {
      assertEquals(2, harness.service.applyBatch(harness.events).appliedCount);
      assertFalse(harness.timing.hasFailed());
      harness.assertQuiescent();
    }
    // The actual existing advice must also suppress its original span-null nested branches.
    for (boolean nestedTarget : new boolean[] {false, true}) {
      try (Harness harness = new Harness(null)) {
        LocalDateTime time = LocalDateTime.of(2026, 10, 31, 12, 0);
        harness.leaf.duringStat =
            () -> {
              if (nestedTarget) harness.mapper.updateStat(stat(), "nested-original", time);
              else assertSame(harness.leaf.source, harness.mapper.dataSource());
            };
        var serviceScope =
            harness.timing.serviceEnter(ProductMetricPipelineLoadSupport.Stage.SETUP, "apply", 1);
        try {
          harness.mapper.updateStat(stat(), "outer-original", time);
        } finally {
          harness.timing.serviceExit(
              serviceScope, false, ProductMetricPipelineLoadSupport.Stage.SETUP);
        }
        assertEquals(
            nestedTarget
                ? List.of(STAT_SQL, STAT_SQL, PROJECTION_SQL, PROJECTION_SQL)
                : List.of(STAT_SQL, PROJECTION_SQL),
            harness.leaf.updates);
        assertEquals(1, harness.state().path("skippedNested").asLong());
        assertEquals(1, harness.state().path("partialFrames").asLong());
        assertEquals(0, harness.state().path("normalFullPairs").asLong());
        // Original timing already rejects nested timed Mapper entries; its policy is unchanged.
        assertEquals(nestedTarget, harness.timing.hasFailed());
        harness.assertQuiescent();
      }
    }
  }

  @Test
  void originalSpringTransactionBindingCommitFailureAndCleanupRemainUnchanged() throws Exception {
    for (boolean rollbackOnCommit : new boolean[] {false, true}) {
      try (Harness harness = new Harness(null)) {
        harness.manager.setRollbackOnCommitFailure(rollbackOnCommit);
        SQLException original = new SQLException("original commit leaf");
        doThrow(original).when(harness.leaf.connection).commit();
        TransactionSystemException actual =
            assertThrows(
                TransactionSystemException.class, () -> harness.service.applyBatch(harness.events));
        assertSame(original, actual.getCause());
        assertEquals(2, harness.state().path("normalFullPairs").asLong());
        verify(harness.leaf.source, times(1)).getConnection();
        verify(harness.leaf.connection).commit();
        verify(harness.leaf.connection, times(rollbackOnCommit ? 1 : 0)).rollback();
        verify(harness.leaf.connection).close();
        assertTrue(harness.leaf.transactionBoundAtAllBusinessStatements);
        assertTrue(harness.leaf.timeouts.stream().allMatch(timeout -> timeout > 0 && timeout <= 3));
        assertEquals(harness.leaf.preparedSql.size(), harness.leaf.timeouts.size());
        harness.assertQuiescent();
      }
    }
  }

  /** Original Boot-created consumers for the cold factory proof, without business startup. */
  @Configuration(proxyBeanMethods = false)
  @Import(ProductOnlineMetricsPipelineIT.StatementEnvelopeConfiguration.class)
  static class ColdBeans {
    /** Construct the actual production Mapper with the selected original resource. */
    @Bean
    ProductMetricMapper mapper(JdbcTemplate template) {
      return new ProductMetricMapper(template);
    }

    /** Construct the real local manager once without acquiring a connection. */
    @Bean
    DataSourceTransactionManager transactionManager(DataSource source) {
      return new DataSourceTransactionManager(source);
    }
  }

  /** Match the actual Pipeline import topology while leaving custom operations wholly owned. */
  @Configuration(proxyBeanMethods = false)
  @Import(ProductOnlineMetricsPipelineIT.StatementEnvelopeConfiguration.class)
  static class ColdFactoryImport {}

  /** Keep null bindings explicit while retaining every non-null object identity. */
  private enum NullValue {
    INSTANCE
  }

  /**
   * Read all six original logger enablement booleans without altering a category or final field.
   */
  private static List<Boolean> loggerVector(JdbcTemplate template) {
    Log logger = (Log) ReflectionTestUtils.getField(template, "logger");
    return List.of(
        logger.isTraceEnabled(),
        logger.isDebugEnabled(),
        logger.isInfoEnabled(),
        logger.isWarnEnabled(),
        logger.isErrorEnabled(),
        logger.isFatalEnabled());
  }

  /** Read the existing refresh seam; no Unsafe or invented constructor bypass is used. */
  @SuppressWarnings("unchecked")
  private static ThreadLocal<ProductOnlineMetricsPipelineIT.Pipeline> current() throws Exception {
    Field field = ProductOnlineMetricsPipelineIT.class.getDeclaredField("CURRENT");
    field.setAccessible(true);
    return (ThreadLocal<ProductOnlineMetricsPipelineIT.Pipeline>) field.get(null);
  }

  /** Stop at the actual first actor before filesystem, SDK, client, database or process action. */
  private static ProductOnlineMetricsPipelineIT.Pipeline partialPipeline(boolean enabled)
      throws Exception {
    return partialPipeline(enabled, false);
  }

  /** Exercise the actual final constructor and refresh after all observer inputs have changed. */
  private static ProductOnlineMetricsPipelineIT.Pipeline partialPipeline(
      boolean enabled, boolean mutateAfterCapture) throws Exception {
    System.setProperty(ProductMetricStatementEnvelopeSupport.FLAG, Boolean.toString(enabled));
    var query =
        ProductMetricQueryInfoCacheSupport.capture(
            ProductMetricPipelineLoadSupport.ROLE,
            300,
            ProductMetricPipelineLoadSupport.Mode.BATCH1);
    var statement = ProductMetricStatementEnvelopeSupport.captureAdmission(query);
    if (mutateAfterCapture) {
      for (String name :
          List.of(
              ProductMetricStatementEnvelopeSupport.FLAG,
              "cloudmall.cm21.single10.application-timing",
              "cloudmall.cm21.single10.callback-service-observation",
              "cloudmall.cm21.single10.service-transaction-observation"))
        System.setProperty(name, "invalid-after-capture");
    }
    AtomicReference<ProductOnlineMetricsPipelineIT.Pipeline> captured = new AtomicReference<>();
    List<String> actors = new ArrayList<>();
    ProductOnlineMetricsPipelineIT.Pipeline.ADMISSION_OBSERVER.set(captured::set);
    ProductMetricQueryInfoCacheSupport.ACTOR_HOOK.set(
        (kind, admission) -> {
          actors.add(kind);
          throw new StopBeforeActor();
        });
    try {
      if (mutateAfterCapture) {
        var incompatibleQuery =
            ProductMetricQueryInfoCacheSupport.admit(
                "false",
                true,
                ProductMetricPipelineLoadSupport.ROLE,
                ProductMetricPipelineLoadSupport.Mode.SINGLE1,
                false,
                1,
                300,
                "false",
                "false");
        assertThrows(
            IllegalStateException.class,
            () ->
                new ProductOnlineMetricsPipelineIT.Pipeline(
                    ProductMetricPipelineLoadSupport.ROLE,
                    300,
                    ProductMetricPipelineLoadSupport.Mode.SINGLE1,
                    incompatibleQuery,
                    statement));
        assertTrue(actors.isEmpty());
        assertNull(captured.get());
      }
      assertThrows(
          StopBeforeActor.class,
          () -> {
            if (mutateAfterCapture)
              new ProductOnlineMetricsPipelineIT.Pipeline(
                  ProductMetricPipelineLoadSupport.ROLE,
                  300,
                  ProductMetricPipelineLoadSupport.Mode.BATCH1,
                  query,
                  statement);
            else
              new ProductOnlineMetricsPipelineIT.Pipeline(
                  ProductMetricPipelineLoadSupport.ROLE,
                  300,
                  ProductMetricPipelineLoadSupport.Mode.BATCH1);
          });
      assertNotNull(captured.get());
      assertEquals(enabled, captured.get().statementEnvelope != null);
      assertEquals(List.of("mkdir"), actors);
      if (mutateAfterCapture) {
        assertSame(query, captured.get().queryInfoAdmission);
        assertSame(statement, captured.get().statementAdmission);
        assertNotNull(captured.get().applicationTiming);
        assertNotNull(captured.get().callbackServiceObservation);
        assertNotNull(captured.get().serviceTransactionObservation);
        assertEquals(
            "invalid-after-capture",
            System.getProperty(ProductMetricStatementEnvelopeSupport.FLAG));
      }
      return captured.get();
    } finally {
      ProductOnlineMetricsPipelineIT.Pipeline.ADMISSION_OBSERVER.remove();
      ProductMetricQueryInfoCacheSupport.ACTOR_HOOK.remove();
    }
  }

  /** Return the controlled fixture inputs to their exact valid test values between matrix rows. */
  private static void restoreObserverInputs() {
    System.setProperty(ProductMetricStatementEnvelopeSupport.FLAG, "false");
    for (String name :
        List.of(
            "cloudmall.cm21.single10.application-timing",
            "cloudmall.cm21.single10.callback-service-observation",
            "cloudmall.cm21.single10.service-transaction-observation"))
      System.setProperty(name, "true");
  }

  /** Private control signal is raised before the real actor and never enters business code. */
  private static final class StopBeforeActor extends RuntimeException {}

  /** Restore all relevant global inputs after executing the genuine actor-free entry chain. */
  private static final class PropertiesScope implements AutoCloseable {
    /** Exact original values, including absence. */
    private final Map<String, String> original = new LinkedHashMap<>();

    /** Set one valid Single10 shape without creating any resource. */
    private PropertiesScope() {
      Map<String, String> values =
          Map.of(
              "cloudmall.cm21.owner", OWNER,
              "cloudmall.cm21.pipeline-batch", "true",
              "cloudmall.cm21.metric-consumers", "1",
              "cloudmall.cm21.phase-timing", "false",
              "cloudmall.cm21.phase-anchor", "false",
              "cloudmall.cm21.single10.application-timing", "true",
              "cloudmall.cm21.single10.callback-service-observation", "true",
              "cloudmall.cm21.single10.service-transaction-observation", "true",
              "cloudmall.cm21.single10.client-queryinfo-cache", "false");
      for (var entry : values.entrySet()) {
        original.put(entry.getKey(), System.getProperty(entry.getKey()));
        System.setProperty(entry.getKey(), entry.getValue());
      }
      original.put(
          ProductMetricStatementEnvelopeSupport.FLAG,
          System.getProperty(ProductMetricStatementEnvelopeSupport.FLAG));
    }

    /** Restore absence and original bytes rather than leaving disabled flags behind. */
    @Override
    public void close() {
      original.forEach(
          (name, value) -> {
            if (value == null) System.clearProperty(name);
            else System.setProperty(name, value);
          });
    }
  }

  /** Create a local collector with no Pipeline, IO or production configuration. */
  private static ProductMetricStatementEnvelopeSupport collector(LongSupplier clock) {
    return new ProductMetricStatementEnvelopeSupport(
        OWNER + "-batch1",
        ProductMetricPipelineLoadSupport.Mode.BATCH1,
        clock,
        () -> ProductMetricPipelineLoadSupport.Stage.SETUP);
  }

  /** Exact aggregate scalars expose binding order and null event identity. */
  private static ProductOnlineStatPO stat() {
    ProductOnlineStatPO stat = new ProductOnlineStatPO();
    stat.productId = 41L;
    stat.viewCount = 3L;
    stat.searchCount = 4L;
    stat.soldCount = 5L;
    stat.hotScore = 57L;
    stat.metricVersion = 6L;
    return stat;
  }

  /** Actual production code/proxies; only JDBC interfaces and the unused loader are doubles. */
  private static final class Harness implements AutoCloseable {
    /** Local registry has no component scan or external auto configuration. */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** One controlled original datasource and JDBC leaves. */
    private final Leaf leaf = new Leaf();

    /** Original configured translation result, reused without observer wrapping. */
    private final DataAccessResourceFailureException translation =
        new DataAccessResourceFailureException("original translator");

    /** Independent observer keeps its clock fault domain separate from legacy timing. */
    private final ProductMetricStatementEnvelopeSupport observation;

    /** Factory-created subclass delegates every original business update to Spring JDBC. */
    private final JdbcTemplate template;

    /** Original real local transaction manager. */
    private final DataSourceTransactionManager manager;

    /** Existing timing advice receives the sole optional observer. */
    private final ProductMetricApplicationTimingSupport timing;

    /** Actual production Mapper under its original timing proxy. */
    private final ProductMetricMapper mapper;

    /** Actual production Service under its original transaction and load proxy. */
    private final ProductMetricService service;

    /** Existing stage owner supplies the actual new marker qualification. */
    private final ProductMetricPipelineLoadSupport load;

    /** Canonical events retain original ordering through actual batch logic. */
    private final List<ProductMetricEventDTO> events =
        List.of(
            ProductMetricEvents.product("11111111-1111-4111-8111-111111111111", 41L, false, null),
            ProductMetricEvents.product("22222222-2222-4222-8222-222222222222", 42L, false, null));

    /** Original advisor object identities must remain unchanged. */
    private final org.springframework.aop.Advisor[] serviceAdvisors, mapperAdvisors;

    /** Assemble real code around controlled JDBC leaves without cold connection acquisition. */
    private Harness(LongSupplier clock) throws Exception {
      ProductMetricPipelineLoadSupport[] holder = new ProductMetricPipelineLoadSupport[1];
      AtomicLong time = new AtomicLong();
      observation =
          new ProductMetricStatementEnvelopeSupport(
              OWNER + "-batch1",
              ProductMetricPipelineLoadSupport.Mode.BATCH1,
              clock == null ? () -> time.getAndAdd(100) : clock,
              () -> holder[0].currentStage());
      template =
          ProductMetricStatementEnvelopeSupport.createTemplate(
              leaf.source, new JdbcProperties(), observation);
      manager = new DataSourceTransactionManager(leaf.source);
      timing =
          new ProductMetricApplicationTimingSupport(
              OWNER + "-batch1",
              ProductMetricPipelineLoadSupport.Mode.BATCH1,
              () -> time.getAndAdd(100),
              null,
              observation);
      mapper = (ProductMetricMapper) timing.installMapper(new ProductMetricMapper(template));
      ProductMetricService target = new ProductMetricService(mapper);
      ProxyFactory proxy = new ProxyFactory(target);
      proxy.setProxyTargetClass(true);
      proxy.addAdvice(
          new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
      service = (ProductMetricService) proxy.getProxy();
      load =
          new ProductMetricPipelineLoadSupport(
              ProductMetricPipelineLoadSupport.Mode.BATCH1, timing);
      holder[0] = load;
      load.install(service);
      serviceAdvisors = ((Advised) service).getAdvisors().clone();
      mapperAdvisors = ((Advised) mapper).getAdvisors().clone();
      context.registerBean(DataSource.class, () -> leaf.source);
      context.registerBean("jdbcTemplate", JdbcTemplate.class, () -> template);
      context.registerBean(PlatformTransactionManager.class, () -> manager);
      context.registerBean(ProductMetricMapper.class, () -> mapper);
      context.registerBean(ProductMetricService.class, () -> service);
      context.registerBean(
          ProductIndexDocumentLoader.class, () -> mock(ProductIndexDocumentLoader.class));
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "local", Map.of("cloudmall.product.metrics.enabled", "true")));
      context.refresh();
      assertTrue(timing.verifyStatementEnvelopeRuntime(context, leaf.source));
      assertNull(ReflectionTestUtils.getField(template, "exceptionTranslator"));
      verify(leaf.source, never()).getConnection();
      template.setExceptionTranslator(
          (task, sql, problem) -> {
            translation.initCause(problem);
            return translation;
          });
      leaf.warning = null;
    }

    /** Read only bounded diagnostic counters. */
    private JsonNode state() {
      return JSON.valueToTree(
              observation.snapshot(ProductMetricStatementEnvelopeSupport.SnapshotKind.COMPLETION))
          .path("collectorState");
    }

    /** Check proxy identity and Spring's actual connection/thread cleanup. */
    private void assertQuiescent() {
      assertArrayEquals(serviceAdvisors, ((Advised) service).getAdvisors());
      assertArrayEquals(mapperAdvisors, ((Advised) mapper).getAdvisors());
      assertSame(leaf.source, manager.getDataSource());
      assertSame(leaf.source, mapper.dataSource());
      assertFalse(TransactionSynchronizationManager.hasResource(leaf.source));
      assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
      assertEquals(0, state().path("activeFrames").asLong());
    }

    /** Close the local registry only; no database or middleware exists in this fixture. */
    @Override
    public void close() {
      context.close();
    }
  }

  /**
   * Only JDBC leaves implement row contents and external faults; Mapper/template are never mocked.
   */
  private static final class Leaf {
    /** One original source reference is shared by real Mapper and transaction manager. */
    private final DataSource source = mock(DataSource.class);

    /** Actual transaction manager and Spring JDBC both use this external connection leaf. */
    private final Connection connection = mock(Connection.class);

    /** Exact SQL object references received from Spring JDBC. */
    private final List<String> preparedSql = new ArrayList<>();

    /** DML order includes failed attempts. */
    private final List<String> updates = new ArrayList<>();

    /** Bound values exist only in test leaves, never inside the observer. */
    private final List<List<Object>> boundValues = new ArrayList<>();

    /** Timeout values actually applied by the original remaining-transaction-TTL path. */
    private final List<Integer> timeouts = new ArrayList<>();

    /** Warning leaf permits comparison of original logger-sensitive behavior. */
    private SQLWarning warning = new SQLWarning("controlled warning");

    /** Actual warning reads and statement closes. */
    private int warningReads, closedStatements;

    /** External row counts preserve original business row gates. */
    private int statRows = 1, projectionRows = 1, ledgerCalls;

    /** Original business failures are retained solely by the test. */
    private Throwable statFailure, projectionFailure, secondLedgerFailure;

    /** One external JDBC reentry exercises the real advice's original nested fast path. */
    private Runnable duringStat;

    /** Recursive JDBC reentry is finite and occurs only once per test leaf. */
    private boolean ranDuringStat;

    /** Every statement of a Service transaction must see its real original bound resource. */
    private boolean transactionBoundAtAllBusinessStatements = true;

    /** Supply JDBC interfaces without starting a connection or any background actor. */
    private Leaf() throws Exception {
      when(source.getConnection()).thenReturn(connection);
      when(connection.getAutoCommit()).thenReturn(true);
      when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
      java.sql.DatabaseMetaData metadata = mock(java.sql.DatabaseMetaData.class);
      when(connection.getMetaData()).thenReturn(metadata);
      when(metadata.getDatabaseProductName()).thenReturn("controlled");
      when(metadata.getDriverName()).thenReturn("controlled");
      when(connection.prepareStatement(anyString()))
          .thenAnswer(
              invocation -> {
                String sql = invocation.getArgument(0);
                preparedSql.add(sql);
                if (!TransactionSynchronizationManager.hasResource(source))
                  transactionBoundAtAllBusinessStatements = false;
                Map<Integer, Object> parameters = new HashMap<>();
                return mock(
                    PreparedStatement.class,
                    call -> {
                      String method = call.getMethod().getName();
                      Object[] arguments = call.getArguments();
                      if (method.equals("getConnection")) return connection;
                      if (method.equals("getParameterMetaData")) {
                        java.sql.ParameterMetaData parameterMetadata =
                            mock(java.sql.ParameterMetaData.class);
                        when(parameterMetadata.getParameterType(anyInt()))
                            .thenReturn(java.sql.Types.VARCHAR);
                        return parameterMetadata;
                      }
                      if (method.equals("setQueryTimeout")) {
                        timeouts.add((Integer) arguments[0]);
                        return null;
                      }
                      if (method.equals("getWarnings")) {
                        warningReads++;
                        return warning;
                      }
                      if (method.equals("close")) {
                        closedStatements++;
                        return null;
                      }
                      if (method.equals("setNull")) {
                        parameters.put((Integer) arguments[0], NullValue.INSTANCE);
                        return null;
                      }
                      if (method.startsWith("set")
                          && arguments.length >= 2
                          && arguments[0] instanceof Integer) {
                        parameters.put((Integer) arguments[0], arguments[1]);
                        return null;
                      }
                      if (method.equals("executeUpdate")) {
                        updates.add(sql);
                        boundValues.add(
                            new ArrayList<>(new java.util.TreeMap<>(parameters).values()));
                        if (sql.equals(STAT_SQL)) {
                          if (duringStat != null && !ranDuringStat) {
                            ranDuringStat = true;
                            duringStat.run();
                          }
                          if (statFailure != null) throw statFailure;
                          return statRows;
                        }
                        if (sql.equals(PROJECTION_SQL)) {
                          if (projectionFailure != null) throw projectionFailure;
                          return projectionRows;
                        }
                        if (sql.contains("product_metric_applied_event(event_id")) {
                          ledgerCalls++;
                          if (ledgerCalls == 2 && secondLedgerFailure != null)
                            throw secondLedgerFailure;
                        }
                        return 1;
                      }
                      if (method.equals("executeQuery")) return result(sql, parameters);
                      return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
                    });
              });
    }

    /** Deliver a controlled ordinary RR row to the actual production row mapper. */
    private ResultSet result(String sql, Map<Integer, Object> parameters) throws Exception {
      Map<String, Object> row = new HashMap<>();
      row.put("singleton_id", 1);
      row.put("series_id", ProductMetricEvents.SERIES);
      row.put("revision", 0L);
      row.put("index_ready", 0);
      row.put("mapping_present", true);
      if (sql.startsWith("SELECT * FROM product_online_stat")) {
        row.put("product_id", parameters.get(1));
        for (String column :
            List.of("view_count", "search_count", "sold_count", "hot_score", "metric_version"))
          row.put(column, 0L);
      }
      ResultSet result = mock(ResultSet.class);
      when(result.next()).thenReturn(true, false);
      when(result.getObject(anyString())).thenAnswer(call -> row.get(call.getArgument(0)));
      when(result.getString(anyString()))
          .thenAnswer(
              call -> {
                Object value = row.get(call.getArgument(0));
                return value == null ? null : value.toString();
              });
      when(result.getBoolean(anyString()))
          .thenAnswer(call -> Boolean.TRUE.equals(row.get(call.getArgument(0))));
      return result;
    }

    /** Count exact SQL attempts without normalizing or prefix-matching the target statements. */
    private long roleCount(String sql) {
      return updates.stream().filter(sql::equals).count();
    }
  }
}
