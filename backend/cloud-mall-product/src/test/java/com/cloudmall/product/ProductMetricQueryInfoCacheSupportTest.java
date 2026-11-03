package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.jdbc.JdbcConnection;
import com.mysql.cj.jdbc.JdbcPropertySetImpl;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline contracts execute real admission, IT constructor and ownership code over JDBC leaves. */
class ProductMetricQueryInfoCacheSupportTest {
  /** Explicit original local base, never actually contacted by these tests. */
  private static final String BASE = "jdbc:mysql://127.0.0.1:53931/";

  /** A syntactically valid product-owned catalog used only by controlled JDBC leaves. */
  private static final String CATALOG = "cm21_offline_product";

  /** Independent prior source/admin identity, not derived from each subsequent raw. */
  private static final String UUID_A = "11111111-1111-1111-1111-111111111111";

  /** A mismatching instance identity must never replace the trusted expected value. */
  private static final String UUID_B = "22222222-2222-2222-2222-222222222222";

  /** Distinct scalar driver IDs; audits do not retain the corresponding connection objects. */
  private static final AtomicLong CONNECTION_IDS = new AtomicLong();

  /** Standard JUnit scratch files for bounded CREATE_NEW sidecars, not any database resources. */
  @TempDir Path directory;

  /**
   * Missing/false preserve original URL bytes while distinguishing control auditing from legacy.
   */
  @Test
  void missingAndFalseKeepOriginalUrlBytes() throws Exception {
    try (PropertyScope ignored = new PropertyScope()) {
      for (String request : new String[] {null, "false"}) {
        property(ProductMetricQueryInfoCacheSupport.FLAG, request);
        var controlled =
            ProductMetricQueryInfoCacheSupport.capture(
                ProductMetricPipelineLoadSupport.ROLE,
                300,
                ProductMetricPipelineLoadSupport.Mode.BATCH1);
        assertEquals(ProductMetricQueryInfoCacheSupport.Policy.CONTROL_FALSE, controlled.policy());
        String original =
            BASE
                + CATALOG
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai&connectTimeout=3000&socketTimeout=5000";
        assertEquals(original, ProductMetricQueryInfoCacheSupport.url(BASE, CATALOG, controlled));
        assertArrayEquals(
            original.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            ProductMetricQueryInfoCacheSupport.url(BASE, CATALOG, controlled)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Leaf leaf = new Leaf(false, UUID_A);
        List<String> actualUrls = new ArrayList<>();
        var dataSource = owned(controlled, leaf, actualUrls);
        try (Connection connection = dataSource.getConnection()) {
          assertSame(leaf.raw, connection.unwrap(JdbcConnection.class));
          assertEquals(1, dataSource.open.get());
        }
        assertEquals(List.of(original), actualUrls);
        assertEquals(1, leaf.uuidQueries.get());
        assertEquals(0, dataSource.open.get());
        assertCounter(dataSource.queryInfoAudit.snapshot(), "delivered", 1);
        dataSource.queryInfoAudit.requireQuiescent();
        property("cloudmall.cm21.pipeline-batch", "false");
        var legacy = ProductMetricQueryInfoCacheSupport.capture("convergence", 8, null);
        assertEquals(ProductMetricQueryInfoCacheSupport.Policy.LEGACY_DISABLED, legacy.policy());
        assertEquals(original, ProductMetricQueryInfoCacheSupport.url(BASE, CATALOG, legacy));
        property("cloudmall.cm21.pipeline-batch", "true");
      }
    }
  }

  /** The true strategy reaches the actual owned DS leaf with exactly four fixed suffixes. */
  @Test
  void trueAppendsOnlyFourFixedClientCacheProperties() throws Exception {
    try (PropertyScope ignored = new PropertyScope()) {
      property(ProductMetricQueryInfoCacheSupport.FLAG, "true");
      var admission =
          ProductMetricQueryInfoCacheSupport.capture(
              ProductMetricPipelineLoadSupport.ROLE,
              300,
              ProductMetricPipelineLoadSupport.Mode.BATCH1);
      assertEquals(ProductMetricQueryInfoCacheSupport.Policy.CANDIDATE_TRUE, admission.policy());
      String expected =
          BASE
              + CATALOG
              + ProductMetricQueryInfoCacheSupport.ORIGINAL_PARAMETERS
              + "&cachePrepStmts=true&useServerPrepStmts=false&prepStmtCacheSize=25&prepStmtCacheSqlLimit=256";
      assertEquals(expected, ProductMetricQueryInfoCacheSupport.url(BASE, CATALOG, admission));
      Leaf leaf = new Leaf(true, UUID_A);
      List<String> urls = new ArrayList<>();
      var source = owned(admission, leaf, urls);
      try (Connection connection = source.getConnection()) {
        assertSame(leaf.raw, connection.unwrap(JdbcConnection.class));
      }
      assertEquals(List.of(expected), urls);
      assertEquals(List.of(3), leaf.uuidTimeouts);
      assertEquals(
          List.of("SELECT DATABASE()", "SET time_zone='+08:00'", "SELECT @@server_uuid"),
          leaf.commands);
      assertEquals(1, leaf.closeCalls.get());
      source.queryInfoAudit.requireQuiescent();
      for (String key :
          List.of(
              "cachePrepStmts",
              "useServerPrepStmts",
              "prepStmtCacheSize",
              "prepStmtCacheSqlLimit")) {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProductMetricQueryInfoCacheSupport.url(
                    BASE + "?" + key + "=false&", CATALOG, admission));
        assertEquals(1, expected.split(key + "=", -1).length - 1);
      }
    }
  }

  /** Actual Single10/IT constructor chains reject before actors and consume frozen shape fields. */
  @Test
  void invalidBooleanAndUnsupportedSingle10ShapesFailBeforeActors() throws Exception {
    try (PropertyScope ignored = new PropertyScope()) {
      List<String> actors = new ArrayList<>();
      ProductMetricQueryInfoCacheSupport.ACTOR_HOOK.set(
          (kind, admission) -> {
            actors.add(kind);
            throw new StopAtActor();
          });
      // 1. Every malformed boolean reaches the real Single10 entry and direct Pipeline entry.
      for (String malformed : List.of("", "TRUE", "False", " true", "false ", "1", "null")) {
        property(ProductMetricQueryInfoCacheSupport.FLAG, malformed);
        assertThrows(
            IllegalArgumentException.class,
            () -> ProductOnlineMetricsPipelineIT.Single10.main(new String[] {"batch1"}));
        assertThrows(
            IllegalArgumentException.class,
            () -> new ProductOnlineMetricsPipelineIT.Pipeline("load", 300));
        assertTrue(actors.isEmpty());
      }
      property(ProductMetricQueryInfoCacheSupport.FLAG, "true");
      for (var forgedPolicy : ProductMetricQueryInfoCacheSupport.Policy.values()) {
        String forgedRequest =
            forgedPolicy == ProductMetricQueryInfoCacheSupport.Policy.CANDIDATE_TRUE
                ? "false"
                : "true";
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ProductMetricQueryInfoCacheSupport.Admission(
                    forgedPolicy,
                    forgedRequest,
                    true,
                    ProductMetricPipelineLoadSupport.ROLE,
                    ProductMetricPipelineLoadSupport.Mode.BATCH1,
                    true,
                    1,
                    300,
                    "false",
                    "false"));
      }
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ProductMetricQueryInfoCacheSupport.Admission(
                  ProductMetricQueryInfoCacheSupport.Policy.CANDIDATE_TRUE,
                  "true",
                  false,
                  "load",
                  null,
                  true,
                  1,
                  300,
                  "false",
                  "false"));
      assertThrows(
          IllegalStateException.class,
          () ->
              new ProductMetricQueryInfoCacheSupport.Admission(
                  ProductMetricQueryInfoCacheSupport.Policy.CANDIDATE_TRUE,
                  "true",
                  true,
                  ProductMetricPipelineLoadSupport.ROLE,
                  ProductMetricPipelineLoadSupport.Mode.BATCH1,
                  true,
                  2,
                  300,
                  "false",
                  "false"));
      assertTrue(actors.isEmpty());
      assertThrows(
          IllegalArgumentException.class,
          () -> new ProductOnlineMetricsPipelineIT.Pipeline("load", 300));
      assertThrows(
          IllegalStateException.class,
          () ->
              new ProductOnlineMetricsPipelineIT.Pipeline(
                  ProductMetricPipelineLoadSupport.ROLE,
                  300,
                  ProductMetricPipelineLoadSupport.Mode.SINGLE1));
      for (String[] change :
          List.of(
              new String[] {"cloudmall.cm21.pipeline-batch", "false"},
              new String[] {"cloudmall.cm21.metric-consumers", "2"},
              new String[] {"cloudmall.cm21.phase-timing", "true"},
              new String[] {"cloudmall.cm21.phase-anchor", "true"})) {
        String previous = System.getProperty(change[0]);
        property(change[0], change[1]);
        assertThrows(
            IllegalStateException.class,
            () -> ProductOnlineMetricsPipelineIT.Single10.main(new String[] {"batch1"}));
        assertTrue(actors.isEmpty());
        property(change[0], previous);
      }
      assertThrows(
          IllegalStateException.class,
          () ->
              new ProductOnlineMetricsPipelineIT.Pipeline(
                  ProductMetricPipelineLoadSupport.ROLE,
                  299,
                  ProductMetricPipelineLoadSupport.Mode.BATCH1));
      assertTrue(actors.isEmpty());
      // 2. Freeze once, then mutate every qualifying property before the actual constructor.
      var captured =
          ProductMetricQueryInfoCacheSupport.capture(
              ProductMetricPipelineLoadSupport.ROLE,
              300,
              ProductMetricPipelineLoadSupport.Mode.BATCH1);
      AtomicInteger assignedSnapshots = new AtomicInteger();
      ProductOnlineMetricsPipelineIT.Pipeline.ADMISSION_OBSERVER.set(
          run -> {
            assertSame(captured, run.queryInfoAdmission);
            assertEquals(1, run.metricConsumers);
            assertTrue(run.batchMode);
            assignedSnapshots.incrementAndGet();
          });
      mutateShapeAfterCapture();
      ProductMetricQueryInfoCacheSupport.ACTOR_HOOK.set(
          (kind, admission) -> {
            assertSame(captured, admission);
            actors.add(kind);
            throw new StopAtActor();
          });
      assertThrows(
          StopAtActor.class,
          () ->
              new ProductOnlineMetricsPipelineIT.Pipeline(
                  ProductMetricPipelineLoadSupport.ROLE,
                  300,
                  ProductMetricPipelineLoadSupport.Mode.BATCH1,
                  captured));
      assertEquals(1, assignedSnapshots.get());
      assertEquals(List.of("mkdir"), actors); // The hook throws BEFORE Files.createDirectories.
      // 3. Single10 captures before claim; even a capture-hook property change cannot reselect it.
      resetShape();
      property(ProductMetricQueryInfoCacheSupport.FLAG, "true");
      actors.clear();
      ProductMetricQueryInfoCacheSupport.CAPTURE_HOOK.set(
          admission -> {
            assertEquals(
                ProductMetricQueryInfoCacheSupport.Policy.CANDIDATE_TRUE, admission.policy());
            mutateShapeAfterCapture();
          });
      ProductMetricQueryInfoCacheSupport.ACTOR_HOOK.set(
          (kind, admission) -> {
            assertEquals(
                ProductMetricQueryInfoCacheSupport.Policy.CANDIDATE_TRUE, admission.policy());
            assertEquals(1, admission.consumers());
            actors.add(kind);
            throw new StopAtActor();
          });
      assertThrows(
          StopAtActor.class,
          () -> ProductOnlineMetricsPipelineIT.Single10.main(new String[] {"batch1"}));
      assertEquals(
          List.of("claim"), actors); // No claim/mkdir/inspect/connection/HttpClient/Nacos executed.
    }
  }

  /**
   * Independent legacy DB/Stock constructors ignore even malformed flags and create no new audit.
   */
  @Test
  void legacyAndStockConstructionRemainDisabled() throws Exception {
    try (PropertyScope ignored = new PropertyScope()) {
      for (String flag : List.of("invalid", "true", "false")) {
        property(ProductMetricQueryInfoCacheSupport.FLAG, flag);
        var oldProduct = new ProductOnlineMetricsMySqlIT.OwnedDataSource(BASE, CATALOG);
        var stock = new ProductOnlineMetricsMySqlIT.OwnedDataSource(BASE, "cm21_offline_stock");
        assertEquals(
            ProductMetricQueryInfoCacheSupport.Policy.LEGACY_DISABLED,
            oldProduct.queryInfoAdmission.policy());
        assertEquals(
            ProductMetricQueryInfoCacheSupport.Policy.LEGACY_DISABLED,
            stock.queryInfoAdmission.policy());
        assertNull(oldProduct.queryInfoAudit);
        assertNull(stock.queryInfoAudit);
        assertNull(oldProduct.expectedServerUuid);
        assertNull(stock.expectedServerUuid);
      }
      Leaf legacyLeaf = new Leaf(false, UUID_A);
      List<String> urls = new ArrayList<>();
      var legacy = owned(ProductMetricQueryInfoCacheSupport.legacy(), legacyLeaf, urls);
      try (Connection connection = legacy.getConnection()) {
        assertSame(legacyLeaf.raw, connection.unwrap(JdbcConnection.class));
      }
      assertEquals(0, legacyLeaf.uuidQueries.get());
      assertNull(legacy.queryInfoAudit);
      assertEquals(
          List.of(BASE + CATALOG + ProductMetricQueryInfoCacheSupport.ORIGINAL_PARAMETERS), urls);
      assertTrue(
          ProductMetricQueryInfoCacheSupport.publish(
              directory,
              "startup",
              "ignored",
              "ignored",
              ProductMetricQueryInfoCacheSupport.legacy(),
              null,
              null));
      assertEquals(0, Files.list(directory).count());
      // Existing OwnedDatabase entry delegates legacy before its first connection: no flag parse.
      property("cloudmall.cm21.mysql.base-url", null);
      IllegalStateException missingBase =
          assertThrows(
              IllegalStateException.class,
              () -> new ProductOnlineMetricsMySqlIT.OwnedDatabase("stock", "offline"));
      assertEquals("CM21_EXPLICIT_LOOPBACK_MYSQL_BASE_REQUIRED", missingBase.getMessage());
      resetShape();
      property(ProductMetricQueryInfoCacheSupport.FLAG, "false");
      var control =
          ProductMetricQueryInfoCacheSupport.capture(
              ProductMetricPipelineLoadSupport.ROLE,
              300,
              ProductMetricPipelineLoadSupport.Mode.BATCH1);
      Leaf controlledLeaf = new Leaf(false, UUID_A);
      var controlled = owned(control, controlledLeaf, new ArrayList<>());
      try (Connection connection = controlled.getConnection()) {
        assertNotNull(controlled.queryInfoAudit);
      }
      assertEquals(1, controlledLeaf.uuidQueries.get());
      assertThrows(
          IllegalArgumentException.class,
          () -> new ProductOnlineMetricsMySqlIT.OwnedDatabase("stock", "offline", control));
    }
  }

  /**
   * Failures before/after proxy preparation preserve primary causes and honest ownership totals.
   */
  @Test
  void physicalPropertyReadbackFailureClosesConnectionWithoutLeakingOwnership() throws Exception {
    try (PropertyScope ignored = new PropertyScope()) {
      var admission =
          ProductMetricQueryInfoCacheSupport.capture(
              ProductMetricPipelineLoadSupport.ROLE,
              300,
              ProductMetricPipelineLoadSupport.Mode.BATCH1);
      // 1. A pre-established source A cannot be overwritten by a controlled raw reporting B.
      Leaf sourceAnchor = new Leaf(false, UUID_A);
      String trusted = ProductMetricQueryInfoCacheSupport.serverUuid(sourceAnchor.raw);
      assertEquals(UUID_A, trusted);
      sourceAnchor.raw.close();
      Leaf wrongServer = new Leaf(false, UUID_B);
      var wrongSource =
          new ProductOnlineMetricsMySqlIT.OwnedDataSource(
              BASE, CATALOG, admission, trusted, (url, username, password) -> wrongServer.raw);
      assertEquals(
          "CM21_QUERYINFO_UUID_MISMATCH",
          assertThrows(SQLException.class, wrongSource::getConnection).getMessage());
      assertEquals(1, wrongServer.closeCalls.get());
      assertOwnership(wrongSource, 1, 0, 1, 1, 0);
      Leaf wrongFlags = new Leaf(true, UUID_A);
      var wrongFlagSource = owned(admission, wrongFlags, new ArrayList<>());
      assertEquals(
          "CM21_QUERYINFO_EFFECTIVE_MISMATCH",
          assertThrows(SQLException.class, wrongFlagSource::getConnection).getMessage());
      assertOwnership(wrongFlagSource, 1, 0, 1, 1, 0);
      Leaf rejectionErrorLeaf = new Leaf(true, UUID_A);
      var rejectionErrorSource = owned(admission, rejectionErrorLeaf, new ArrayList<>());
      AssertionError rejectionAuditError = new AssertionError("rejected audit leaf Error");
      ProductMetricQueryInfoCacheSupport.AUDIT_HOOK.set(
          event -> {
            if (event.equals("REJECTED")) throw rejectionAuditError;
          });
      SQLException retainedReadback =
          assertThrows(SQLException.class, rejectionErrorSource::getConnection);
      assertEquals("CM21_QUERYINFO_EFFECTIVE_MISMATCH", retainedReadback.getMessage());
      assertNull(retainedReadback.getCause());
      assertEquals(1, retainedReadback.getSuppressed().length);
      assertSame(rejectionAuditError, retainedReadback.getSuppressed()[0].getCause());
      assertEquals(1, rejectionErrorLeaf.closeCalls.get());
      assertOwnership(rejectionErrorSource, 1, 0, 1, 1, 0);
      ProductMetricQueryInfoCacheSupport.AUDIT_HOOK.remove();
      // 2. Preserve proxy-prepare failure; a second close failure is only suppressed and
      // unreleased.
      Leaf sameFailureLeaf = new Leaf(false, UUID_A);
      SQLException sameFailure = new SQLException("same validation and close leaf", "08000", 92);
      when(sameFailureLeaf.raw.getCatalog()).thenThrow(sameFailure);
      doThrow(sameFailure).when(sameFailureLeaf.raw).close();
      var sameFailureSource = owned(admission, sameFailureLeaf, new ArrayList<>());
      assertSame(sameFailure, assertThrows(SQLException.class, sameFailureSource::getConnection));
      assertEquals(0, sameFailure.getSuppressed().length);
      assertOwnership(sameFailureSource, 1, 0, 1, 0, 1);
      assertCounter(sameFailureSource.queryInfoAudit.snapshot(), "closeFailedAttempts", 1);
      assertEquals("FAILED", sameFailureSource.queryInfoAudit.snapshot().get("status"));
      verify(sameFailureLeaf.raw).close();
      Leaf proxyFailureLeaf = new Leaf(false, UUID_A);
      SQLException closeFault = new SQLException("close leaf", "08000", 91);
      doThrow(closeFault).when(proxyFailureLeaf.raw).close();
      var proxyFailureSource = owned(admission, proxyFailureLeaf, new ArrayList<>());
      IllegalStateException original = new IllegalStateException("proxy preparation leaf");
      ProductMetricQueryInfoCacheSupport.PROXY_HOOK.set(
          () -> {
            throw original;
          });
      assertSame(
          original, assertThrows(IllegalStateException.class, proxyFailureSource::getConnection));
      assertTrue(List.of(original.getSuppressed()).contains(closeFault));
      assertOwnership(proxyFailureSource, 1, 0, 1, 0, 1);
      assertCounter(proxyFailureSource.queryInfoAudit.snapshot(), "validated", 1);
      assertCounter(proxyFailureSource.queryInfoAudit.snapshot(), "closeFailedAttempts", 1);
      ProductMetricQueryInfoCacheSupport.PROXY_HOOK.remove();
      // 3. Audit preparation rejects a prepared proxy without leaking original open ownership.
      Leaf auditLeaf = new Leaf(false, UUID_A);
      var auditSource = owned(admission, auditLeaf, new ArrayList<>());
      ProductMetricQueryInfoCacheSupport.AUDIT_HOOK.set(
          event -> {
            if (event.equals("DELIVERED"))
              throw new IllegalStateException("audit preparation leaf");
          });
      assertThrows(SQLException.class, auditSource::getConnection);
      assertOwnership(auditSource, 1, 0, 1, 1, 0);
      assertEquals("FAILED", auditSource.queryInfoAudit.snapshot().get("status"));
      ProductMetricQueryInfoCacheSupport.AUDIT_HOOK.remove();
      // 4. Delivered close failure retains open until an explicit successful retry; FAILED is
      // sticky.
      Leaf deliveredLeaf = new Leaf(false, UUID_A);
      var deliveredSource = owned(admission, deliveredLeaf, new ArrayList<>());
      Connection delivered = deliveredSource.getConnection();
      doThrow(closeFault).doNothing().when(deliveredLeaf.raw).close();
      AssertionError closeAuditError = new AssertionError("close failure audit leaf Error");
      ProductMetricQueryInfoCacheSupport.AUDIT_HOOK.set(
          event -> {
            if (event.equals("DELIVERED_CLOSE_FAILED")) throw closeAuditError;
          });
      assertSame(closeFault, assertThrows(SQLException.class, delivered::close));
      assertTrue(
          java.util.Arrays.stream(closeFault.getSuppressed())
              .anyMatch(failure -> failure.getCause() == closeAuditError));
      ProductMetricQueryInfoCacheSupport.AUDIT_HOOK.remove();
      assertEquals(1, deliveredSource.open.get());
      assertCounter(deliveredSource.queryInfoAudit.snapshot(), "unreleased", 1);
      delivered.close();
      delivered.close();
      assertOwnership(deliveredSource, 1, 1, 0, 1, 0);
      assertEquals("FAILED", deliveredSource.queryInfoAudit.snapshot().get("status"));
      verify(deliveredLeaf.raw, times(2)).close();
      // 5. A close-audit fault is suppressed by Java TWR onto an existing business primary.
      Leaf closeAuditLeaf = new Leaf(false, UUID_A);
      var closeAuditSource = owned(admission, closeAuditLeaf, new ArrayList<>());
      Connection closeAuditConnection = closeAuditSource.getConnection();
      ProductMetricQueryInfoCacheSupport.AUDIT_HOOK.set(
          event -> {
            if (event.equals("DELIVERED_CLOSED"))
              throw new IllegalStateException("close audit leaf");
          });
      IllegalArgumentException business = new IllegalArgumentException("business primary");
      assertSame(
          business,
          assertThrows(
              IllegalArgumentException.class,
              () -> {
                try (Connection connection = closeAuditConnection) {
                  throw business;
                }
              }));
      assertEquals(1, business.getSuppressed().length);
      assertInstanceOf(SQLException.class, business.getSuppressed()[0]);
      assertOwnership(closeAuditSource, 1, 1, 0, 1, 0);
      assertEquals("FAILED", closeAuditSource.queryInfoAudit.snapshot().get("status"));
      ProductMetricQueryInfoCacheSupport.AUDIT_HOOK.remove();
      // 6. Acquisition failure does not manufacture a created raw or an ownership count.
      SQLException acquireFault = new SQLException("acquire leaf");
      var acquireSource =
          new ProductOnlineMetricsMySqlIT.OwnedDataSource(
              BASE,
              CATALOG,
              admission,
              UUID_A,
              (url, username, password) -> {
                throw acquireFault;
              });
      assertSame(acquireFault, assertThrows(SQLException.class, acquireSource::getConnection));
      assertCounter(acquireSource.queryInfoAudit.snapshot(), "acquisitionFailed", 1);
      assertOwnership(acquireSource, 0, 0, 0, 0, 0);
    }
  }

  /**
   * Both arms validate every raw beyond pool/sample bounds and preserve bounded ordered evidence.
   */
  @Test
  void effectivePropertyEvidenceKeepsBothComparisonArmsSymmetric() throws Exception {
    try (PropertyScope ignored = new PropertyScope()) {
      List<Map<String, Object>> snapshots = new ArrayList<>();
      for (boolean cache : List.of(false, true)) {
        property(ProductMetricQueryInfoCacheSupport.FLAG, Boolean.toString(cache));
        var admission =
            ProductMetricQueryInfoCacheSupport.capture(
                ProductMetricPipelineLoadSupport.ROLE,
                300,
                ProductMetricPipelineLoadSupport.Mode.BATCH1);
        ConcurrentLinkedQueue<Leaf> leaves = new ConcurrentLinkedQueue<>();
        var source =
            new ProductOnlineMetricsMySqlIT.OwnedDataSource(
                BASE,
                CATALOG,
                admission,
                UUID_A,
                (url, username, password) -> {
                  assertEquals(
                      ProductMetricQueryInfoCacheSupport.url(BASE, CATALOG, admission), url);
                  Leaf leaf = new Leaf(cache, UUID_A);
                  leaves.add(leaf);
                  return leaf.raw;
                });
        // 1. Twenty-four physical creations exceed both 12 pool slots and 16 evidence samples.
        var workers = Executors.newFixedThreadPool(4);
        try {
          List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
          for (int index = 0; index < 24; index++)
            futures.add(
                workers.submit(
                    () -> {
                      try (Connection connection = source.getConnection()) {
                        assertNotNull(connection.unwrap(JdbcConnection.class));
                      } catch (SQLException failure) {
                        throw new IllegalStateException(failure);
                      }
                    }));
          for (var future : futures) future.get(20, TimeUnit.SECONDS);
        } finally {
          workers.shutdown();
          assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
        assertEquals(24, leaves.size());
        for (Leaf leaf : leaves) {
          assertEquals(1, leaf.uuidQueries.get());
          assertEquals(List.of(3), leaf.uuidTimeouts);
          assertEquals(1, leaf.closeCalls.get());
        }
        assertOwnership(source, 24, 24, 0, 24, 0);
        source.queryInfoAudit.requireQuiescent();
        Map<String, Object> terminal = source.queryInfoAudit.terminalSnapshot();
        assertCounter(terminal, "inFlight", 0);
        assertCounter(terminal, "unreleased", 0);
        Map<String, Object> snapshot = source.queryInfoAudit.snapshot();
        assertCounter(snapshot, "validated", 24);
        assertCounter(snapshot, "sequence", 120);
        assertEquals(16, ((List<?>) snapshot.get("samples")).size());
        assertEquals(true, snapshot.get("sampleTruncated"));
        assertTrue(((String) snapshot.get("orderedSha256")).matches("[0-9a-f]{64}"));
        long sequence = 0;
        for (Object sample : (List<?>) snapshot.get("samples")) {
          Map<?, ?> row = (Map<?, ?>) sample;
          assertEquals(++sequence, row.get("sequence"));
          assertInstanceOf(Long.class, row.get("threadId"));
          assertTrue(((String) row.get("threadName")).length() <= 128);
          Map<?, ?> facts = (Map<?, ?>) row.get("facts");
          if (row.get("event").equals("VALIDATED")) {
            assertNotNull(facts.get("actualImplementationCodeSource"));
            assertEquals(JdbcConnection.class.getName(), facts.get("connectorApiClass"));
            assertTrue(
                ((String) facts.get("connectorApiJar")).endsWith("mysql-connector-j-8.0.33.jar"));
            assertTrue(((String) facts.get("connectorApiJarSha256")).matches("[0-9a-f]{64}"));
          }
        }
        snapshots.add(snapshot);
        // 2. Four independent bounded files; repeating CREATE_NEW fails without overwriting bytes.
        Path arm = Files.createDirectory(directory.resolve(cache ? "true" : "false"));
        for (String kind : List.of("startup", "measurement-ended", "finish-10", "completion"))
          assertTrue(
              ProductMetricQueryInfoCacheSupport.publish(
                  arm, kind, "a".repeat(32), "runtime", admission, UUID_A, source.queryInfoAudit));
        byte[] original = Files.readAllBytes(arm.resolve("startup.queryinfo-cache.json"));
        assertTrue(original.length < 131_072);
        assertFalse(
            ProductMetricQueryInfoCacheSupport.publish(
                arm,
                "startup",
                "a".repeat(32),
                "runtime",
                admission,
                UUID_A,
                source.queryInfoAudit));
        assertArrayEquals(
            original, Files.readAllBytes(arm.resolve("startup.queryinfo-cache.json")));
        assertEquals("FAILED", source.queryInfoAudit.snapshot().get("status"));
        assertEquals("SIDECAR_PUBLICATION", source.queryInfoAudit.snapshot().get("failureCode"));
      }
      for (String counter :
          List.of(
              "created",
              "acquisitionFailed",
              "validated",
              "delivered",
              "rejected",
              "closed",
              "open",
              "unreleased",
              "inFlight",
              "sequence"))
        assertEquals(snapshots.get(0).get(counter), snapshots.get(1).get(counter));
      // 3. Counter and sequence overflows fail before a raw factory, never wrap or deliver.
      var admission =
          ProductMetricQueryInfoCacheSupport.capture(
              ProductMetricPipelineLoadSupport.ROLE,
              300,
              ProductMetricPipelineLoadSupport.Mode.BATCH1);
      AtomicInteger acquired = new AtomicInteger();
      var overflow =
          new ProductOnlineMetricsMySqlIT.OwnedDataSource(
              BASE,
              CATALOG,
              admission,
              UUID_A,
              (url, username, password) -> {
                acquired.incrementAndGet();
                return new Leaf(true, UUID_A).raw;
              });
      Field sequence = ProductMetricQueryInfoCacheSupport.Audit.class.getDeclaredField("sequence");
      sequence.setAccessible(true);
      sequence.setLong(overflow.queryInfoAudit, Long.MAX_VALUE);
      assertThrows(SQLException.class, overflow::getConnection);
      assertEquals(0, acquired.get());
      assertEquals(0, overflow.open.get());
      assertEquals("COUNTER_OVERFLOW", overflow.queryInfoAudit.snapshot().get("failureCode"));
      var counterOverflow = new ProductMetricQueryInfoCacheSupport.Audit();
      Field countersField =
          ProductMetricQueryInfoCacheSupport.Audit.class.getDeclaredField("counters");
      countersField.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, Long> counters = (Map<String, Long>) countersField.get(counterOverflow);
      counters.put("inFlight", Long.MAX_VALUE);
      assertThrows(SQLException.class, counterOverflow::begin);
      assertEquals("COUNTER_OVERFLOW", counterOverflow.snapshot().get("failureCode"));
    }
  }

  /** Build the actual owned DS with only acquisition replaced by a controlled JDBC leaf. */
  private static ProductOnlineMetricsMySqlIT.OwnedDataSource owned(
      ProductMetricQueryInfoCacheSupport.Admission admission, Leaf leaf, List<String> urls) {
    return new ProductOnlineMetricsMySqlIT.OwnedDataSource(
        BASE,
        CATALOG,
        admission,
        admission.controlled() ? UUID_A : null,
        (url, username, password) -> {
          urls.add(url);
          assertEquals("root", username);
          assertEquals("root", password);
          return leaf.raw;
        });
  }

  /** Assert terminal conservation including rejected-but-unclosed resources and original open. */
  private static void assertOwnership(
      ProductOnlineMetricsMySqlIT.OwnedDataSource source,
      long created,
      long delivered,
      long rejected,
      long closed,
      long unreleased) {
    Map<String, Object> snapshot = source.queryInfoAudit.snapshot();
    assertCounter(snapshot, "created", created);
    assertCounter(snapshot, "delivered", delivered);
    assertCounter(snapshot, "rejected", rejected);
    assertCounter(snapshot, "closed", closed);
    assertCounter(snapshot, "unreleased", unreleased);
    assertCounter(snapshot, "inFlight", 0);
    assertEquals(created, delivered + rejected);
    assertEquals(((Number) snapshot.get("open")).intValue(), source.open.get());
    assertTrue(delivered <= ((Number) snapshot.get("validated")).longValue());
    assertTrue(((Number) snapshot.get("validated")).longValue() <= created);
  }

  /** Compare numeric evidence without JSON/boxed-type ambiguity. */
  private static void assertCounter(Map<String, Object> snapshot, String name, long expected) {
    assertEquals(expected, ((Number) snapshot.get(name)).longValue(), name);
  }

  /** Set or clear a property explicitly so missing and exact false remain distinguishable. */
  private static void property(String name, String value) {
    if (value == null) System.clearProperty(name);
    else System.setProperty(name, value);
  }

  /** Restore the known legal shape without reading any external environment or endpoints. */
  private static void resetShape() {
    property("cloudmall.cm21.pipeline-batch", "true");
    property("cloudmall.cm21.metric-consumers", "1");
    property("cloudmall.cm21.phase-timing", "false");
    property("cloudmall.cm21.phase-anchor", "false");
  }

  /** Mutate every frozen shape input after capture; real entry/ctor must still use the snapshot. */
  private static void mutateShapeAfterCapture() {
    property(ProductMetricQueryInfoCacheSupport.FLAG, "bad-after-capture");
    property("cloudmall.cm21.pipeline-batch", "bad-after-capture");
    property("cloudmall.cm21.metric-consumers", "bad-after-capture");
    property("cloudmall.cm21.phase-timing", "bad-after-capture");
    property("cloudmall.cm21.phase-anchor", "bad-after-capture");
  }

  /** Intentional pre-operation sentinel, proving no operation after the hook was executed. */
  private static final class StopAtActor extends RuntimeException {}

  /** Test-scoped properties/hooks are restored even when any assertion or client leaf throws. */
  private static final class PropertyScope implements AutoCloseable {
    /** Prior values for the finite qualification/observer and fixture property vocabulary. */
    private final Map<String, String> before = new LinkedHashMap<>();

    /** Pin legal observer defaults so unrelated system flags cannot activate actual resources. */
    private PropertyScope() {
      for (String key :
          List.of(
              ProductMetricQueryInfoCacheSupport.FLAG,
              "cloudmall.cm21.pipeline-batch",
              "cloudmall.cm21.metric-consumers",
              "cloudmall.cm21.phase-timing",
              "cloudmall.cm21.phase-anchor",
              "cloudmall.cm21.owner",
              "cloudmall.cm21.mysql.base-url",
              "cloudmall.cm21.single10.application-timing",
              "cloudmall.cm21.single10.callback-service-observation",
              "cloudmall.cm21.single10.service-transaction-observation"))
        before.put(key, System.getProperty(key));
      property(ProductMetricQueryInfoCacheSupport.FLAG, null);
      resetShape();
      property("cloudmall.cm21.owner", "a".repeat(32));
      for (String suffix :
          List.of(
              "application-timing",
              "callback-service-observation",
              "service-transaction-observation"))
        property("cloudmall.cm21.single10." + suffix, "false");
    }

    /** Release all hook state and restore each prior property exactly. */
    @Override
    public void close() {
      ProductMetricQueryInfoCacheSupport.ACTOR_HOOK.remove();
      ProductMetricQueryInfoCacheSupport.CAPTURE_HOOK.remove();
      ProductMetricQueryInfoCacheSupport.PROXY_HOOK.remove();
      ProductMetricQueryInfoCacheSupport.AUDIT_HOOK.remove();
      ProductOnlineMetricsPipelineIT.Pipeline.ADMISSION_OBSERVER.remove();
      before.forEach(ProductMetricQueryInfoCacheSupportTest::property);
    }
  }

  /** Real Connector/J PropertySet and controlled JDBC leaves; no strategy subject is mocked. */
  private static final class Leaf {
    /** Controlled raw driver leaf, also returned by the actual unwrap call. */
    final JdbcConnection raw = mock(JdbcConnection.class);

    /** Ordered real policy-issued statements for this one physical acquisition. */
    final List<String> commands = new ArrayList<>();

    /** Exactly one UUID query per successfully validated controlled physical connection. */
    final AtomicInteger uuidQueries = new AtomicInteger();

    /** Recorded statement timeout at the UUID execute call, not a simulated server timeout. */
    final List<Integer> uuidTimeouts = new ArrayList<>();

    /** Successful leaf closes; raw failure injection is separately observed with Mockito. */
    final AtomicInteger closeCalls = new AtomicInteger();

    /** Install a real driver property set and JDBC-only metadata/result leaves. */
    private Leaf(boolean cache, String serverUuid) throws SQLException {
      Properties properties = new Properties();
      properties.setProperty(PropertyKey.cachePrepStmts.getKeyName(), Boolean.toString(cache));
      properties.setProperty(PropertyKey.useServerPrepStmts.getKeyName(), "false");
      properties.setProperty(PropertyKey.prepStmtCacheSize.getKeyName(), "25");
      properties.setProperty(PropertyKey.prepStmtCacheSqlLimit.getKeyName(), "256");
      JdbcPropertySetImpl propertySet = new JdbcPropertySetImpl();
      propertySet.initializeProperties(properties);
      when(raw.getPropertySet()).thenReturn(propertySet);
      when(raw.getCatalog()).thenReturn(CATALOG);
      when(raw.unwrap(JdbcConnection.class)).thenReturn(raw);
      when(raw.getId()).thenReturn(CONNECTION_IDS.incrementAndGet());
      DatabaseMetaData metadata = mock(DatabaseMetaData.class);
      when(metadata.getDriverVersion()).thenReturn("mysql-connector-j-8.0.33");
      when(raw.getMetaData()).thenReturn(metadata);
      doAnswer(
              invocation -> {
                closeCalls.incrementAndGet();
                return null;
              })
          .when(raw)
          .close();
      when(raw.createStatement())
          .thenAnswer(
              invocation -> {
                Statement statement = mock(Statement.class);
                AtomicInteger timeout = new AtomicInteger();
                doAnswer(
                        call -> {
                          timeout.set(call.getArgument(0));
                          return null;
                        })
                    .when(statement)
                    .setQueryTimeout(anyInt());
                when(statement.execute(anyString()))
                    .thenAnswer(
                        call -> {
                          commands.add(call.getArgument(0));
                          return false;
                        });
                when(statement.executeQuery(anyString()))
                    .thenAnswer(
                        call -> {
                          String sql = call.getArgument(0);
                          commands.add(sql);
                          String value;
                          if (sql.equals("SELECT DATABASE()")) value = CATALOG;
                          else if (sql.equals("SELECT @@server_uuid")) {
                            uuidQueries.incrementAndGet();
                            uuidTimeouts.add(timeout.get());
                            value = serverUuid;
                          } else throw new SQLException("Unexpected offline fixture query");
                          ResultSet result = mock(ResultSet.class);
                          when(result.next()).thenReturn(true, false);
                          when(result.getString(1)).thenReturn(value);
                          return result;
                        });
                return statement;
              });
    }
  }
}
