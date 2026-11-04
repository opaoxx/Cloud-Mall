package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.product.config.ProductReadExceptionHandler;
import com.cloudmall.product.controller.ProductController;
import com.cloudmall.product.domain.dto.ProductDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.mapper.*;
import com.cloudmall.product.service.*;
import com.cloudmall.product.service.impl.index.ProductIndexDocumentLoader;
import com.cloudmall.product.service.impl.index.ProductIndexOutboxService;
import com.cloudmall.product.service.impl.metrics.ProductMetricService;
import com.cloudmall.product.service.impl.search.ElasticsearchProductKeywordSearch;
import com.cloudmall.product.util.ProductMetricEvents;
import com.fasterxml.jackson.databind.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/**
 * Genuine Product JDBC transactions and loopback HTTP; broker/ES are exercised separately in
 * Pipeline.
 */
class ProductOnlineMetricsMySqlIT {
  /** Each Spring refresh obtains only its explicitly owned data source. */
  private static final ThreadLocal<OwnedDatabase> CURRENT = new ThreadLocal<>();

  /** Native evidence is created under the standard module target, never an Agent-specific path. */
  static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void realPublicReadsRegisterFinalPageAndRejectUndurableEvent() throws Exception {
    try (OwnedDatabase db = new OwnedDatabase("product", "public-reads")) {
      db.seedProducts(3);
      Map<String, Object> history = db.tableFacts("product_hot_stat");
      try (ConfigurableApplicationContext context = start(db)) {
        ProductController controller = context.getBean(ProductController.class);
        ProductMetricService metrics = context.getBean(ProductMetricService.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(metrics));
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        HttpResponse<String> first = get(port, "/api/products/41"),
            second = get(port, "/api/products/41");
        assertEquals(200, first.statusCode());
        assertEquals(200, second.statusCode());
        assertNotEquals(
            JSON.readTree(first.body()).path("requestId"),
            JSON.readTree(second.body()).path("requestId"));
        assertEquals(2L, db.count("product_metric_outbox"));
        assertEquals(0L, db.count("product_metric_applied_event"));
        HttpResponse<String> search = get(port, "/api/products?keyword=Metric&pageSize=2&status=1");
        assertEquals(200, search.statusCode());
        assertEquals(4L, db.count("product_metric_outbox"));
        get(port, "/api/products?keyword=%20");
        HttpResponse<String> hot = get(port, "/api/products/41/hot-stat");
        assertEquals(
            "ONLINE_EVENT",
            JSON.readTree(hot.body()).path("data").path("online").path("sourceType").asText());
        get(port, "/api/products/skus/71");
        assertEquals(4L, db.count("product_metric_outbox"));
        var target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(controller);
        ElasticsearchProductKeywordSearch controlled =
            org.mockito.Mockito.mock(ElasticsearchProductKeywordSearch.class);
        org.springframework.test.util.ReflectionTestUtils.setField(target, "searchEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(
            target, "productKeywordSearch", controlled);
        long epoch =
            java.time.LocalDateTime.of(2026, 10, 16, 10, 0)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toEpochMilli();
        var stale =
            new com.cloudmall.product.domain.po.ProductSearchHit(
                41, 3, "Metric product 41", new java.math.BigDecimal("12.00"), 1, 6, epoch);
        var current =
            new com.cloudmall.product.domain.po.ProductSearchHit(
                41, 3, "Metric product 41", new java.math.BigDecimal("12.00"), 1, 7, epoch);
        org.mockito.Mockito.when(controlled.search("controlled", null, 1, 0, 20, null))
            .thenReturn(
                new com.cloudmall.product.domain.po.ProductSearchPage(List.of(stale), 1),
                new com.cloudmall.product.domain.po.ProductSearchPage(List.of(current), 1));
        assertEquals(200, get(port, "/api/products?keyword=controlled&status=1").statusCode());
        org.mockito.Mockito.verify(controlled, org.mockito.Mockito.times(2))
            .search("controlled", null, 1, 0, 20, null);
        assertEquals(5L, db.count("product_metric_outbox"));
        org.mockito.Mockito.when(controlled.search("empty", null, 1, 0, 20, null))
            .thenReturn(new com.cloudmall.product.domain.po.ProductSearchPage(List.of(), 0));
        assertEquals(200, get(port, "/api/products?keyword=empty&status=1").statusCode());
        assertEquals(5L, db.count("product_metric_outbox"));
        org.springframework.test.util.ReflectionTestUtils.setField(target, "searchEnabled", false);
        assertEquals(404, get(port, "/api/products/9999").statusCode());
        assertEquals(5L, db.count("product_metric_outbox"));
        db.trigger(
            "cm21_reject_registration", "product_metric_outbox", "CM21_REGISTRATION_REJECTED");
        HttpResponse<String> failed = get(port, "/api/products?keyword=Metric&pageSize=2");
        assertEquals(503, failed.statusCode());
        assertEquals(
            "PRODUCT_METRICS_UNAVAILABLE", JSON.readTree(failed.body()).path("code").asText());
        assertTrue(failed.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(5L, db.count("product_metric_outbox"));
        db.audit(
            "http",
            Map.of(
                "first",
                JSON.readTree(first.body()),
                "second",
                JSON.readTree(second.body()),
                "search",
                JSON.readTree(search.body()),
                "failed",
                JSON.readTree(failed.body()),
                "failureStatus",
                failed.statusCode(),
                "cacheControl",
                failed.headers().firstValue("Cache-Control").orElse("")));
        db.dropTrigger("cm21_reject_registration");
        assertEquals(history, db.tableFacts("product_hot_stat"));
        assertNotNull(controller);
      }
      db.success();
    }
  }

  @Test
  void ledgerProjectionAndLegacyMigrationRemainAtomicAndRepeatable() throws Exception {
    try (OwnedDatabase db = new OwnedDatabase("product", "ledger", true)) {
      db.seedProducts(2);
      Map<String, Object> legacyBeforeFirstMigration = db.allFacts();
      assertEquals(
          new TreeSet<>(db.originalTables), new TreeSet<>(legacyBeforeFirstMigration.keySet()));
      assertEquals(1800L, db.scalar("SELECT SUM(view_count) FROM product_hot_stat"));
      assertEquals(1600L, db.scalar("SELECT SUM(search_count) FROM product_hot_stat"));
      db.audit("legacy-before-first-migration", legacyBeforeFirstMigration);
      db.migrate();
      Map<String, Object> firstMigrationFacts = db.allFacts();
      for (String table : db.originalTables)
        assertEquals(legacyBeforeFirstMigration.get(table), firstMigrationFacts.get(table), table);
      db.audit("first-migration-full-facts", firstMigrationFacts);
      db.jdbc.execute("ALTER TABLE product_online_stat ALTER COLUMN view_count SET DEFAULT 5");
      db.jdbc.update(
          "INSERT INTO"
              + " product_online_stat(product_id,view_count,search_count,sold_count,hot_score,metric_version,created_at,updated_at)"
              + " VALUES(42,9223372036854775807,0,0,9223372036854775807,0,'2026-10-16"
              + " 10:00:00.000','2026-10-16 10:00:00.000')");
      db.audit(
          "boundary-seed",
          Map.of(
              "role",
              "explicit arithmetic boundary only",
              "productId",
              42,
              "viewCount",
              Long.MAX_VALUE,
              "metricVersion",
              0));
      Map<String, Object> history = db.tableFacts("product_hot_stat");
      Map<String, Object> initial = db.tableFacts("product_metric_control");
      db.migrate();
      assertEquals(initial, db.tableFacts("product_metric_control"));
      try (ConfigurableApplicationContext context = start(db)) {
        ProductMetricService metrics = context.getBean(ProductMetricService.class);
        ProductMetricMapper mapper = context.getBean(ProductMetricMapper.class);
        ProductMetricEventDTO event =
            ProductMetricEvents.product(UUID.randomUUID().toString(), 41, false, null);
        assertEquals("APPLIED", metrics.apply(event));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("DUPLICATE", metrics.apply(event));
        assertEquals(1L, db.count("product_metric_applied_event"));
        assertEquals(1L, db.count("product_metric_projection_outbox"));
        assertEquals(
            1L, db.scalar("SELECT revision FROM product_metric_control WHERE singleton_id=1"));
        ProductMetricEventDTO conflicting =
            ProductMetricEvents.parse(ProductMetricEvents.bytes(event));
        conflicting.occurredAt = "2026-01-01T00:00:00.000+08:00";
        assertThrows(ProductMetricEvents.InvalidEvent.class, () -> metrics.apply(conflicting));
        assertEquals(
            1L, db.scalar("SELECT view_count FROM product_online_stat WHERE product_id=41"));
        Map<String, Object> before = db.allFacts();
        db.trigger(
            "cm21_reject_projection",
            "product_metric_projection_outbox",
            "CM21_PROJECTION_REJECTED");
        ProductMetricEventDTO next =
            ProductMetricEvents.product(UUID.randomUUID().toString(), 41, true, null);
        org.springframework.dao.DataAccessException rejected =
            assertThrows(
                org.springframework.dao.DataAccessException.class, () -> metrics.apply(next));
        assertSignal(rejected, "CM21_PROJECTION_REJECTED");
        db.dropTrigger("cm21_reject_projection");
        assertEquals(before, db.allFacts());
        assertEquals("APPLIED", metrics.apply(next));
        assertEquals(
            1L, db.scalar("SELECT view_count FROM product_online_stat WHERE product_id=41"));
        assertEquals(
            1L, db.scalar("SELECT search_count FROM product_online_stat WHERE product_id=41"));
        assertEquals(
            2L, db.scalar("SELECT metric_version FROM product_online_stat WHERE product_id=41"));
        Map<String, Object> beforeOverflow = db.allFacts();
        assertThrows(
            ProductMetricEvents.InvalidEvent.class,
            () ->
                metrics.apply(
                    ProductMetricEvents.product(UUID.randomUUID().toString(), 42, false, null)));
        assertEquals(beforeOverflow, db.allFacts());
        byte[] bad = "{bad".getBytes(StandardCharsets.UTF_8);
        metrics.quarantine("cm21-owned-bad", bad, null, "INVALID_JSON");
        long revision =
            db.scalar("SELECT revision FROM product_metric_control WHERE singleton_id=1");
        metrics.quarantine("cm21-owned-bad", bad, null, "INVALID_JSON");
        assertEquals(
            revision,
            db.scalar("SELECT revision FROM product_metric_control WHERE singleton_id=1"));
        assertEquals(1L, db.count("product_metric_quarantine"));
        assertTrue(metrics.cut().pending());
        Map<String, Object> migrationBefore = db.allFacts();
        db.migrate();
        assertEquals(migrationBefore, db.allFacts());
        assertEquals(history, db.tableFacts("product_hot_stat"));
        mapper.verifySchema();
        db.audit(
            "ledger-outcome",
            Map.of(
                "appliedEvent",
                JSON.readTree(ProductMetricEvents.bytes(event)),
                "fingerprint",
                ProductMetricEvents.fingerprint(event),
                "revision",
                revision,
                "facts",
                db.allFacts()));

        // Batch completion keeps the original component graph and all preceding golden facts.
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(metrics));
        String sourceOwner = UUID.randomUUID().toString();
        metrics.registerViews(UUID.randomUUID().toString(), 41, null);
        metrics.registerViews(UUID.randomUUID().toString(), 41, null);
        var sourceRows = metrics.claim(sourceOwner);
        assertEquals(2, sourceRows.size());
        Map<String, Boolean> mixed = new LinkedHashMap<>();
        mixed.put(sourceRows.get(0).eventId, true);
        mixed.put(sourceRows.get(1).eventId, false);
        String immutableColumns =
            "SELECT event_id,product_id,event_type,fingerprint,payload_json,created_at"
                + " FROM product_metric_outbox ORDER BY event_id";
        var immutableBefore = db.jdbc.queryForList(immutableColumns);
        db.audit("finish-batch-mixed-before", db.allFacts());
        assertEquals(2, metrics.finishBatch(mixed, sourceOwner));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        for (var source : sourceRows) {
          Map<String, Object> stored =
              db.jdbc.queryForMap(
                  "SELECT * FROM product_metric_outbox WHERE event_id=?", source.eventId);
          boolean published = mixed.get(source.eventId);
          assertEquals(published ? "PUBLISHED" : "UNKNOWN", stored.get("status"));
          assertEquals(published ? 0 : 1, ((Number) stored.get("attempts")).intValue());
          assertNull(stored.get("lease_owner"));
          assertNull(stored.get("lease_until"));
          assertArrayEquals(
              source.payloadJson.getBytes(StandardCharsets.UTF_8),
              ((String) stored.get("payload_json")).getBytes(StandardCharsets.UTF_8));
          if (published) {
            assertNotNull(stored.get("published_at"));
            assertNull(stored.get("last_error"));
          } else {
            assertNull(stored.get("published_at"));
            assertEquals("PUBLISH_UNCERTAIN", stored.get("last_error"));
            assertEquals(
                2000000L,
                db.jdbc
                    .queryForObject(
                        "SELECT TIMESTAMPDIFF(MICROSECOND,updated_at,next_retry_at)"
                            + " FROM product_metric_outbox WHERE event_id=?",
                        Long.class,
                        source.eventId)
                    .longValue());
          }
        }
        assertEquals(immutableBefore, db.jdbc.queryForList(immutableColumns));
        db.audit("finish-batch-mixed-after", db.allFacts());

        // The first event has a real live claim; the last actual row already lost its lease.
        String rollbackOwner = UUID.randomUUID().toString();
        metrics.registerViews(UUID.randomUUID().toString(), 41, null);
        var rollbackClaims = metrics.claim(rollbackOwner);
        var firstClaim =
            rollbackClaims.stream()
                .filter(row -> !mixed.containsKey(row.eventId))
                .findFirst()
                .orElseThrow();
        assertEquals(rollbackOwner, firstClaim.leaseOwner);
        assertTrue(firstClaim.leaseUntil.isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
        Map<String, Boolean> sourceRollback = new LinkedHashMap<>();
        sourceRollback.put(firstClaim.eventId, true);
        sourceRollback.put(sourceRows.get(0).eventId, true);
        Map<String, Object> beforeSourceRollback = db.allFacts();
        db.audit(
            "finish-batch-rollback-before",
            Map.of(
                "order",
                new ArrayList<>(sourceRollback.keySet()),
                "owner",
                rollbackOwner,
                "facts",
                beforeSourceRollback));
        IllegalStateException sourceLost =
            assertThrows(
                IllegalStateException.class,
                () -> metrics.finishBatch(sourceRollback, rollbackOwner));
        assertEquals("METRIC_FINISH_OWNER_LOST", sourceLost.getMessage());
        assertEquals(beforeSourceRollback, db.allFacts());
        assertThrows(
            IllegalStateException.class,
            () -> metrics.finishBatch(Map.of(firstClaim.eventId, true), "wrong-" + rollbackOwner));
        assertEquals(beforeSourceRollback, db.allFacts());
        db.audit("finish-batch-rollback-after", db.allFacts());

        // Only this declared owned lease boundary is seeded; no clock or business count is changed.
        LocalDateTime expiredLease =
            LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1).withNano(0);
        db.audit(
            "finish-batch-expired-lease-plan",
            Map.of(
                "eventId",
                firstClaim.eventId,
                "owner",
                rollbackOwner,
                "leaseUntil",
                expiredLease.toString(),
                "facts",
                db.allFacts()));
        assertEquals(
            1,
            db.jdbc.update(
                "UPDATE product_metric_outbox SET lease_until=? WHERE event_id=?"
                    + " AND BINARY lease_owner=BINARY ?",
                expiredLease,
                firstClaim.eventId,
                rollbackOwner));
        Map<String, Object> expiredFacts = db.allFacts();
        assertThrows(
            IllegalStateException.class,
            () -> metrics.finishBatch(Map.of(firstClaim.eventId, true), rollbackOwner));
        assertEquals(expiredFacts, db.allFacts());
        db.audit("finish-batch-expired-lease-after", expiredFacts);

        String projectionOwner = UUID.randomUUID().toString();
        var projectionClaims = metrics.claimProjection(projectionOwner);
        assertEquals(
            List.of(1L, 2L), projectionClaims.stream().map(row -> row.metricVersion).toList());
        assertTrue(
            projectionClaims.stream()
                .allMatch(row -> row.productId == 41L && projectionOwner.equals(row.leaseOwner)));
        long sentVersion =
            context
                .getBean(ProductIndexDocumentLoader.class)
                .loadOnline(List.of(41L))
                .get(0)
                .onlineStat()
                .metricVersion;
        assertEquals(2L, sentVersion);
        // This genuine later application creates an unclaimed version outside the sent snapshot.
        assertEquals(
            "APPLIED",
            metrics.apply(
                ProductMetricEvents.product(UUID.randomUUID().toString(), 41, false, null)));
        db.audit("projection-batch-before", db.allFacts());
        assertEquals(1, metrics.finishProjectionBatch(Map.of(41L, 1L), projectionOwner, true));
        assertEquals(
            "DONE",
            db.jdbc.queryForObject(
                "SELECT status FROM product_metric_projection_outbox"
                    + " WHERE product_id=41 AND metric_version=1",
                String.class));
        assertEquals(
            "PENDING",
            db.jdbc.queryForObject(
                "SELECT status FROM product_metric_projection_outbox"
                    + " WHERE product_id=41 AND metric_version=2",
                String.class));
        assertEquals(
            projectionOwner,
            db.jdbc.queryForObject(
                "SELECT lease_owner FROM product_metric_projection_outbox"
                    + " WHERE product_id=41 AND metric_version=2",
                String.class));
        assertNull(
            db.jdbc.queryForObject(
                "SELECT lease_owner FROM product_metric_projection_outbox"
                    + " WHERE product_id=41 AND metric_version=3",
                String.class));
        Map<Long, Long> projectionRollback = new LinkedHashMap<>();
        projectionRollback.put(41L, sentVersion);
        projectionRollback.put(42L, 1L);
        Map<String, Object> beforeProjectionRollback = db.allFacts();
        db.audit(
            "projection-batch-rollback-before",
            Map.of(
                "order",
                new ArrayList<>(projectionRollback.keySet()),
                "sentVersions",
                projectionRollback,
                "owner",
                projectionOwner,
                "facts",
                beforeProjectionRollback));
        IllegalStateException projectionLost =
            assertThrows(
                IllegalStateException.class,
                () -> metrics.finishProjectionBatch(projectionRollback, projectionOwner, true));
        assertEquals("METRIC_PROJECTION_OWNER_LOST", projectionLost.getMessage());
        assertEquals(beforeProjectionRollback, db.allFacts());
        assertThrows(
            IllegalStateException.class,
            () ->
                metrics.finishProjectionBatch(
                    Map.of(41L, sentVersion), "wrong-" + projectionOwner, true));
        assertEquals(beforeProjectionRollback, db.allFacts());
        db.audit("projection-batch-rollback-after", db.allFacts());
        assertEquals(
            1, metrics.finishProjectionBatch(Map.of(41L, sentVersion), projectionOwner, true));
        assertEquals(
            "DONE",
            db.jdbc.queryForObject(
                "SELECT status FROM product_metric_projection_outbox"
                    + " WHERE product_id=41 AND metric_version=2",
                String.class));
        assertEquals(
            "PENDING",
            db.jdbc.queryForObject(
                "SELECT status FROM product_metric_projection_outbox"
                    + " WHERE product_id=41 AND metric_version=3",
                String.class));
        assertNull(
            db.jdbc.queryForObject(
                "SELECT lease_owner FROM product_metric_projection_outbox"
                    + " WHERE product_id=41 AND metric_version=3",
                String.class));
        assertEquals(
            3L, db.scalar("SELECT metric_version FROM product_online_stat WHERE product_id=41"));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertNull(AuthContext.userId());
        assertEquals(history, db.tableFacts("product_hot_stat"));
        db.audit("finish-batches-outcome", db.allFacts());

        Map<String, Object> beforeDateReads = db.allFacts();
        Map<String, Object> dateWitness = new LinkedHashMap<>();
        Map<String, LocalDateTime> originalDateComponents =
            db.jdbc.queryForObject(
                "SELECT created_at,updated_at,@@session.time_zone AS session_zone"
                    + " FROM product_online_stat WHERE product_id=?",
                (result, row) -> {
                  assertEquals("+08:00", result.getString("session_zone"));
                  Map<String, LocalDateTime> components = new LinkedHashMap<>();
                  for (String column : List.of("created_at", "updated_at")) {
                    Object originalCell = result.getObject(column);
                    assertTrue(originalCell instanceof LocalDateTime);
                    LocalDateTime driverDate = (LocalDateTime) originalCell;
                    String originalText = result.getString(column);
                    LocalDateTime parsedDate = LocalDateTime.parse(originalText.replace(' ', 'T'));
                    assertEquals(parsedDate, driverDate);
                    assertEquals(0, driverDate.getNano() % 1000000);
                    components.put(column, parsedDate);
                    dateWitness.put(
                        column,
                        Map.of(
                            "driverType",
                            originalCell.getClass().getName(),
                            "originalText",
                            originalText,
                            "components",
                            driverDate.toString(),
                            "nanoseconds",
                            driverDate.getNano(),
                            "sessionZone",
                            "+08:00"));
                  }
                  return components;
                },
                41L);
        var mappedDates = mapper.stat(41, false);
        assertEquals(originalDateComponents.get("created_at"), mappedDates.createdAt);
        assertEquals(originalDateComponents.get("updated_at"), mappedDates.updatedAt);
        assertEquals(
            originalDateComponents.get("created_at").getNano(), mappedDates.createdAt.getNano());
        assertEquals(
            originalDateComponents.get("updated_at").getNano(), mappedDates.updatedAt.getNano());
        assertEquals(beforeDateReads, db.allFacts());
        db.audit(
            "localdatetime-driver-mapping",
            Map.of(
                "observed",
                dateWitness,
                "mappedCreatedAt",
                mappedDates.createdAt.toString(),
                "mappedUpdatedAt",
                mappedDates.updatedAt.toString(),
                "facts",
                db.allFacts()));
      }
      db.success();
    }
  }

  @Test
  void contentAndMetricAdmissionUseOneStableCut() throws Exception {
    try (OwnedDatabase db = new OwnedDatabase("product", "cut")) {
      db.seedProducts(3);
      db.jdbc.update(
          "INSERT INTO"
              + " product_index_rebuild_job(job_id,target_index,index_generation,cursor_product_id,batch_size,max_events_per_minute,last_batch_count,status,created_at,updated_at)"
              + " VALUES('cloudmall-product-index','cm21_component_boundary',1,43,50,6000,3,'COMPLETED','2026-10-16"
              + " 10:00:00.000','2026-10-16 10:00:00.000')");
      db.audit(
          "controlled-es-metadata-seed",
          Map.of(
              "claim",
              "MySQL admission component only; real V2 mapping/UUID is Pipeline",
              "generation",
              "cm21-component-uuid"));
      try (ConfigurableApplicationContext context = start(db)) {
        ProductController controller = context.getBean(ProductController.class);
        ProductMetricService metrics = context.getBean(ProductMetricService.class);
        ProductSqlMapper sql = context.getBean(ProductSqlMapper.class);
        PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
        ProductDTO update = new ProductDTO();
        update.categoryId = 3L;
        update.name = "Metric updated";
        update.price = "12.00";
        update.skus = List.of();
        update.parameters = List.of();
        metrics.readiness("cm21-component-uuid", true);
        var cut = metrics.cut();
        assertTrue(cut.admissible());
        var original =
            context.getBean(ProductIndexDocumentLoader.class).loadOnline(List.of(41L)).get(0);
        var online = original.onlineStat();
        var hit =
            new com.cloudmall.product.domain.po.ProductSearchHit(
                original.id(),
                original.categoryId(),
                original.name(),
                original.price(),
                original.status(),
                original.version(),
                original.updatedAt().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
                online.metricVersion,
                ProductMetricEvents.SERIES,
                online.viewCount,
                online.searchCount,
                online.soldCount,
                online.hotScore,
                ProductMetricEvents.contentFingerprint(original),
                ProductMetricEvents.metricFingerprint(online));
        var target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(controller);
        ElasticsearchProductKeywordSearch controlled =
            org.mockito.Mockito.mock(ElasticsearchProductKeywordSearch.class);
        org.springframework.test.util.ReflectionTestUtils.setField(
            target, "metricSortMode", "ONLINE");
        org.springframework.test.util.ReflectionTestUtils.setField(target, "searchEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(
            target, "productKeywordSearch", controlled);
        org.mockito.Mockito.when(controlled.indexUuid(org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn("cm21-component-uuid");
        org.mockito.Mockito.when(
                controlled.search(
                    org.mockito.ArgumentMatchers.eq("controlled"),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.eq(1),
                    org.mockito.ArgumentMatchers.eq(0),
                    org.mockito.ArgumentMatchers.eq(20),
                    org.mockito.ArgumentMatchers.eq("hot"),
                    org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(new com.cloudmall.product.domain.po.ProductSearchPage(List.of(hit), 1));
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        assertEquals(
            200, get(port, "/api/products?keyword=controlled&status=1&sort=hot").statusCode());
        long registered = db.count("product_metric_outbox");
        org.mockito.Mockito.when(
                controlled.search(
                    org.mockito.ArgumentMatchers.eq("controlled"),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.eq(1),
                    org.mockito.ArgumentMatchers.eq(0),
                    org.mockito.ArgumentMatchers.eq(20),
                    org.mockito.ArgumentMatchers.eq("hot"),
                    org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(
                invocation -> {
                  assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                  metrics.apply(
                      ProductMetricEvents.product(UUID.randomUUID().toString(), 41, false, null));
                  return new com.cloudmall.product.domain.po.ProductSearchPage(List.of(hit), 1);
                });
        HttpResponse<String> moved =
            get(port, "/api/products?keyword=controlled&status=1&sort=hot");
        assertEquals(503, moved.statusCode());
        assertEquals("PRODUCT_SEARCH_RETRYABLE", JSON.readTree(moved.body()).path("code").asText());
        assertEquals(registered, db.count("product_metric_outbox"));
        assertFalse(metrics.admit(cut, List.of(hit)));
        org.springframework.test.util.ReflectionTestUtils.setField(target, "searchEnabled", false);
        org.springframework.test.util.ReflectionTestUtils.setField(
            target, "metricSortMode", "LEGACY");
        long before = db.scalar("SELECT revision FROM product_metric_control WHERE singleton_id=1");
        AuthContext.set(9L, "ADMIN");
        try {
          controller.update(41L, update);
          controller.publish(41L);
          controller.unpublish(41L);
          ProductDTO create = new ProductDTO();
          create.categoryId = 3L;
          create.name = "Metric created";
          create.price = "10.00";
          create.skus = List.of();
          create.parameters = List.of();
          controller.create(create);
        } finally {
          AuthContext.clear();
        }
        assertEquals(
            before + 4,
            db.scalar("SELECT revision FROM product_metric_control WHERE singleton_id=1"));
        assertEquals(4L, db.count("product_index_outbox"));
        assertTrue(metrics.cut().pending());
        assertFalse(metrics.cut().admissible());
        Map<String, Object> prior = db.allFacts();
        db.trigger("cm21_reject_content", "product_index_outbox", "CM21_CONTENT_REJECTED");
        AuthContext.set(9L, "ADMIN");
        try {
          assertThrows(
              org.springframework.dao.DataAccessException.class, () -> controller.publish(41L));
        } finally {
          AuthContext.clear();
          db.dropTrigger("cm21_reject_content");
        }
        assertEquals(prior, db.allFacts());
        metrics.apply(ProductMetricEvents.product(UUID.randomUUID().toString(), 42, true, null));
        metrics.apply(ProductMetricEvents.product(UUID.randomUUID().toString(), 43, false, null));
        TransactionTemplate rr = new TransactionTemplate(manager);
        rr.setReadOnly(true);
        rr.setIsolationLevel(
            org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var page =
            rr.execute(
                status -> {
                  long count = sql.countProducts(null, null, 1);
                  var rows = sql.findProductsOnline(null, null, 1, 0, 20, "hot");
                  long revision = metrics.sqlRevision();
                  return Map.of("total", count, "rows", rows, "revision", revision);
                });
        assertEquals(
            List.of(43L, 42L),
            ((List<Map<String, Object>>) page.get("rows"))
                .stream().limit(2).map(row -> ((Number) row.get("id")).longValue()).toList());
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertNull(AuthContext.userId());
        // This SQL-only component has no ES dispatcher: unresolved real obligations stay closed.
        assertFalse(metrics.readiness("cm21-component-uuid", true));
        var pendingCut = metrics.cut();
        assertEquals(ProductMetricEvents.SERIES, pendingCut.series());
        assertEquals("cm21-component-uuid", pendingCut.indexUuid());
        assertFalse(pendingCut.ready());
        assertTrue(pendingCut.pending());
        assertFalse(pendingCut.admissible());
        assertTrue(pendingCut.revision() > cut.revision());
        assertFalse(metrics.admit(cut, List.of(hit)));
        assertFalse(metrics.admit(pendingCut, List.of(hit)));
        db.audit("cut-outcome", Map.of("page", page, "facts", db.allFacts()));
      }
      db.success();
    }
  }

  private static ConfigurableApplicationContext start(OwnedDatabase db) {
    CURRENT.set(db);
    try {
      ConfigurableApplicationContext context =
          new SpringApplicationBuilder(SqlApplication.class)
              .profiles("cm21-product-mysql-it")
              .run(
                  "--server.address=127.0.0.1",
                  "--server.port=0",
                  "--spring.main.banner-mode=off",
                  "--logging.level.root=WARN",
                  "--spring.cloud.nacos.discovery.enabled=false",
                  "--spring.cloud.nacos.config.enabled=false",
                  "--seata.enabled=false",
                  "--seata.enable-auto-data-source-proxy=false",
                  "--cloudmall.product.metrics.enabled=true",
                  "--cloudmall.product.metrics.sort-mode=LEGACY",
                  "--cloudmall.product.search.enabled=false");
      db.contexts.add(context);
      db.ownedPorts.add(((ServletWebServerApplicationContext) context).getWebServer().getPort());
      return context;
    } finally {
      CURRENT.remove();
    }
  }

  private static HttpResponse<String> get(int port, String path) throws Exception {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Explicit test-only graph excludes production network components in this MySQL component suite.
   */
  @SpringBootConfiguration
  @EnableAutoConfiguration(
      excludeName = {
        "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration"
      })
  @Profile("cm21-product-mysql-it")
  @MapperScan("com.cloudmall.product.mapper")
  @Import({
    ProductController.class,
    com.cloudmall.product.service.impl.ProductQueryServiceImpl.class,
    com.cloudmall.product.service.impl.ProductManagementServiceImpl.class,
    com.cloudmall.product.service.impl.CategoryServiceImpl.class,
    com.cloudmall.product.service.impl.SeckillActivityServiceImpl.class,
    com.cloudmall.product.service.impl.ProductCatalogReader.class,
    com.cloudmall.product.util.ProductValueCodec.class,
    ProductReadExceptionHandler.class,
    GlobalExceptionHandler.class,
    ProductMetricService.class,
    ProductMetricMapper.class,
    ProductSqlMapper.class,
    ProductIndexOutboxMapper.class,
    ProductIndexOutboxService.class,
    ProductIndexDocumentLoader.class
  })
  public static class SqlApplication {
    /** Exactly the owned physical resource is injected into JDBC, MyBatis and the original TM. */
    @Bean
    public DataSource dataSource() {
      return Objects.requireNonNull(CURRENT.get()).dataSource;
    }

    /**
     * Existing public reads do not use Redis; the transport suite uses a real owned Redis instead.
     */
    @Bean
    public StringRedisTemplate redis() {
      return org.mockito.Mockito.mock(StringRedisTemplate.class);
    }

    /** Real timers avoid introducing a second production metrics registry. */
    @Bean
    public io.micrometer.core.instrument.MeterRegistry registry() {
      return new SimpleMeterRegistry();
    }
  }

  /**
   * Test-local exact ownership/facts helper reused only by this module's Pipeline and controlled
   * load entry.
   */
  static final class OwnedDatabase implements AutoCloseable {
    /** Immutable source catalog is only read; all test writes target the UUID catalog. */
    final String sourceName;

    /** Exact newly-created owner identity. */
    final String database;

    /** Every evidence file is CREATE_NEW beneath this standard target run. */
    final Path evidence;

    /** Same physical resource used by original business beans. */
    final OwnedDataSource dataSource;

    /** Owned JDBC; SQL here is fixture setup/assertion only. */
    final JdbcTemplate jdbc;

    /** Immutable source DDL/rows/checksums/index facts held for final exact comparison. */
    final Map<String, Object> sourceBefore;

    /** Original structural table inventory. */
    final List<String> originalTables;

    /** Explicitly declared trigger names, never wildcard-drop cleanup. */
    final Set<String> triggers = new TreeSet<>();

    /** Cleanup is authorized only after caller assertions and all close/ownership gates pass. */
    private boolean passed;

    /** Each created Spring context must be closed before facts qualify for cleanup. */
    final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    /** Exact owned loopback HTTP listeners must refuse connections after context close. */
    final Set<Integer> ownedPorts = new TreeSet<>();

    /** Administrator URL contains no credentials and is never emitted into the audit. */
    private final String base;

    /** Independent source/admin identity anchor; absent for untouched legacy/Stock entry points. */
    final String expectedServerUuid;

    OwnedDatabase(String role, String scenario) throws Exception {
      this(role, scenario, false);
    }

    /** Only the migration scenario defers new DDL until its nonzero legacy facts are pinned. */
    OwnedDatabase(String role, String scenario, boolean deferMigration) throws Exception {
      this(role, scenario, deferMigration, ProductMetricQueryInfoCacheSupport.legacy());
    }

    /** Controlled product entry covers migration, seed, facts and every pooled raw acquisition. */
    OwnedDatabase(
        String role, String scenario, ProductMetricQueryInfoCacheSupport.Admission admission)
        throws Exception {
      this(role, scenario, false, admission);
    }

    /**
     * Preserve original fixture setup while adding only explicit product policy and source UUID.
     */
    private OwnedDatabase(
        String role,
        String scenario,
        boolean deferMigration,
        ProductMetricQueryInfoCacheSupport.Admission admission)
        throws Exception {
      ProductMetricQueryInfoCacheSupport.requireIntrinsic(admission);
      if (admission.controlled() && !"product".equals(role))
        throw new IllegalArgumentException("CM21_QUERYINFO_PRODUCT_ONLY");
      if (!Set.of("product", "stock").contains(role))
        throw new IllegalStateException("SOURCE_ROLE_NOT_ADMITTED");
      base = System.getProperty("cloudmall.cm21.mysql.base-url");
      if (base == null || !base.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"))
        throw new IllegalStateException("CM21_EXPLICIT_LOOPBACK_MYSQL_BASE_REQUIRED");
      sourceName = "cloudmall_" + role;
      database = "cm21_" + UUID.randomUUID().toString().replace("-", "") + "_" + role;
      evidence =
          Path.of(
              "target",
              "cm21-online-metrics",
              scenario,
              System.currentTimeMillis() + "-" + UUID.randomUUID());
      Files.createDirectories(evidence);
      String sourceServerUuid = null;
      try (Connection source = connection(sourceName)) {
        source.setReadOnly(true);
        if (admission.controlled())
          sourceServerUuid = ProductMetricQueryInfoCacheSupport.serverUuid(source);
        originalTables = tables(source);
        if (!originalTables.equals(
            role.equals("product")
                ? List.of(
                    "product",
                    "product_category",
                    "product_hot_stat",
                    "product_index_consumption",
                    "product_index_dlq_quarantine",
                    "product_index_outbox",
                    "product_index_rebuild_job",
                    "product_parameter",
                    "product_sku",
                    "seckill_activity")
                : List.of(
                    "seata_cm18_stock_demo",
                    "seckill_order_outbox",
                    "seckill_quota_settlement",
                    "seckill_reservation",
                    "stock_cache_repair",
                    "stock_consistency_audit_state",
                    "stock_flow",
                    "stock_reservation_request",
                    "stock_sku",
                    "undo_log")))
          throw new IllegalStateException("SOURCE_TABLE_WHITELIST_MISMATCH");
        sourceBefore = facts(source, originalTables);
      }
      expectedServerUuid = sourceServerUuid;
      audit("source-before", sourceBefore);
      audit(
          "ownership-plan",
          Map.of(
              "catalog",
              database,
              "source",
              sourceName,
              "originalTables",
              originalTables,
              "role",
              role));
      try (Connection admin = connection(sourceName);
          Statement statement = admin.createStatement()) {
        statement.execute(
            "CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        for (String table : originalTables)
          statement.execute(
              "CREATE TABLE `"
                  + database
                  + "`.`"
                  + table
                  + "` LIKE `"
                  + sourceName
                  + "`.`"
                  + table
                  + "`");
      }
      dataSource =
          admission.controlled()
              ? new OwnedDataSource(base, database, admission, expectedServerUuid)
              : new OwnedDataSource(base, database);
      jdbc = new JdbcTemplate(dataSource);
      if (!deferMigration) migrate();
      audit("created-facts", allFacts());
    }

    /** Execute only the reviewed idempotent new-table migration on this owned catalog. */
    void migrate() throws Exception {
      String role =
          sourceName.endsWith("product") ? "product_online_metrics" : "stock_sales_outbox";
      Path script = Path.of("..", "database", "cm21_" + role + ".sql");
      if (!Files.isRegularFile(script))
        script = Path.of("backend", "database", "cm21_" + role + ".sql");
      String text = Files.readString(script);
      text = text.replaceAll("(?m)^--.*$", "");
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        for (String sql : text.split(";")) if (!sql.isBlank()) statement.execute(sql);
      }
    }

    /** Deterministic seed-only rows; no imported historical row is copied or reset. */
    void seedProducts(int count) {
      jdbc.update(
          "INSERT INTO product_category(id,parent_id,name,sort_no,status,created_at,updated_at)"
              + " VALUES(3,0,'CM21 owned',0,1,'2026-10-16 10:00:00.000','2026-10-16"
              + " 10:00:00.000')");
      for (int index = 0; index < count; index++) {
        long id = 41L + index;
        jdbc.update(
            "INSERT INTO product(id,category_id,name,price,status,version,created_at,updated_at)"
                + " VALUES(?,3,?,12.00,1,7,'2026-10-16 10:00:00.000','2026-10-16 10:00:00.000')",
            id,
            "Metric product " + id);
        jdbc.update(
            "INSERT INTO"
                + " product_sku(id,product_id,sku_code,spec_json,price,status,created_at,updated_at)"
                + " VALUES(?,?,?,'{}',12.00,1,'2026-10-16 10:00:00.000','2026-10-16 10:00:00.000')",
            71L + index,
            id,
            "cm21-sku-" + id);
        jdbc.update(
            "INSERT INTO"
                + " product_hot_stat(product_id,view_count,search_count,hot_score,stat_date,updated_at,source_type,source_ref)"
                + " VALUES(?,900,800,123.000000,'2026-01-01','2026-10-16"
                + " 10:00:00.000','LEGACY_IMPORT','owned historical control')",
            id);
      }
    }

    /** Install one declared genuine MySQL SIGNAL fault after recording its exact definition. */
    void trigger(String name, String table, String marker) throws Exception {
      if (!name.matches("cm21_[a-z_]+")
          || !originalTables.contains(table)
              && !table.startsWith("product_metric_")
              && !table.equals("stock_sales_outbox"))
        throw new IllegalArgumentException("TRIGGER_PLAN");
      String ddl =
          "CREATE TRIGGER `"
              + name
              + "` BEFORE INSERT ON `"
              + table
              + "` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644,MESSAGE_TEXT='"
              + marker
              + "'";
      audit("trigger-plan-" + name, Map.of("name", name, "table", table, "ddl", ddl));
      jdbc.execute(ddl);
      triggers.add(name);
    }

    /**
     * Remove only the exact trigger declared by this fixture after collecting its SHOW CREATE fact.
     */
    void dropTrigger(String name) throws Exception {
      if (!triggers.contains(name)) throw new IllegalStateException("TRIGGER_NOT_OWNED");
      audit("trigger-before-drop-" + name, jdbc.queryForList("SHOW CREATE TRIGGER `" + name + "`"));
      jdbc.execute("DROP TRIGGER `" + name + "`");
      triggers.remove(name);
    }

    /** Complete all business assertions before enabling exact cleanup. */
    void success() {
      passed = true;
    }

    /** Read a fixture scalar without manufacturing a business transition. */
    long scalar(String sql) {
      return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class));
    }

    /** Count an admitted actual table. */
    long count(String table) {
      if (!table.matches("[a-z_]+")) throw new IllegalArgumentException();
      return scalar("SELECT COUNT(*) FROM `" + table + "`");
    }

    /**
     * Full current facts include DDL, indexes, count, checksum and all ordered string components.
     */
    Map<String, Object> allFacts() throws Exception {
      try (Connection connection = dataSource.getConnection()) {
        return facts(connection, tables(connection));
      }
    }

    /** Full facts for one admitted business table. */
    Map<String, Object> tableFacts(String table) throws Exception {
      try (Connection connection = dataSource.getConnection()) {
        return facts(connection, List.of(table));
      }
    }

    /** Write immutable native JSON without exposing connection strings or credentials. */
    void audit(String name, Object value) throws Exception {
      Files.write(
          evidence.resolve(name + ".json"),
          JSON.writeValueAsBytes(value),
          StandardOpenOption.CREATE_NEW);
    }

    /**
     * Physical connections always select and verify the exact expected catalog and +08:00 session.
     */
    private Connection connection(String catalog) throws SQLException {
      Connection connection =
          DriverManager.getConnection(
              base
                  + catalog
                  + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai&connectTimeout=3000&socketTimeout=5000",
              "root",
              "root");
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET time_zone='+08:00'");
      }
      if (!catalog.equals(connection.getCatalog()) || !catalog.equals(actualCatalog(connection))) {
        connection.close();
        throw new SQLException("OWNED_CATALOG_MISMATCH");
      }
      return connection;
    }

    @Override
    public void close() throws Exception {
      List<String> failures = new ArrayList<>();
      try {
        audit("owned-final", allFacts());
        try (Connection source = connection(sourceName)) {
          source.setReadOnly(true);
          Map<String, Object> after = facts(source, tables(source));
          audit("source-after", after);
          if (!sourceBefore.equals(after)) failures.add("SOURCE_CHANGED");
        }
        if (dataSource.open.get() != 0) failures.add("CONNECTIONS_OPEN");
        if (contexts.stream().anyMatch(ConfigurableApplicationContext::isActive))
          failures.add("CONTEXT_ACTIVE");
        for (int port : ownedPorts) {
          try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            failures.add("HTTP_LISTENER_ACTIVE");
          } catch (java.net.ConnectException stopped) {
            /* exact listener refused */
          }
        }
        Map<String, Object> optionalRootContext = optionalRootContextObservation();
        audit("optional-root-context", optionalRootContext);
        if (TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isSynchronizationActive()
            || AuthContext.userId() != null
            || Boolean.TRUE.equals(optionalRootContext.get("xidPresent")))
          failures.add("THREAD_CONTEXT_OPEN");
        if (!triggers.isEmpty()) failures.add("TRIGGERS_REMAIN");
        try (Connection connection = dataSource.getConnection()) {
          assertObjects(connection);
        }
        if (!passed) failures.add("ASSERTIONS_NOT_COMPLETE");
      } catch (Exception failure) {
        failures.add("FINAL_FACTS_OR_OBJECTS_UNKNOWN");
      }
      audit(
          "cleanup-decision", Map.of("catalog", database, "passed", passed, "failures", failures));
      if (!failures.isEmpty())
        throw new IllegalStateException("CM21_PRESERVED_" + String.join("_", failures));
      try (Connection admin = connection(sourceName);
          Statement statement = admin.createStatement()) {
        if (!database.matches("cm21_[0-9a-f]{32}_(product|stock)"))
          throw new IllegalStateException("OWNER_INVALID");
        statement.execute("DROP DATABASE `" + database + "`");
      }
      audit("cleanup-complete", Map.of("catalog", database, "dropped", true));
    }

    private void assertObjects(Connection connection) throws SQLException {
      try (Statement statement = connection.createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT (SELECT COUNT(*) FROM information_schema.views WHERE"
                      + " table_schema=DATABASE())+(SELECT COUNT(*) FROM"
                      + " information_schema.triggers WHERE trigger_schema=DATABASE())+(SELECT"
                      + " COUNT(*) FROM information_schema.routines WHERE"
                      + " routine_schema=DATABASE())+(SELECT COUNT(*) FROM"
                      + " information_schema.events WHERE event_schema=DATABASE())")) {
        result.next();
        if (result.getLong(1) != 0) throw new SQLException("EXTRA_OBJECTS");
      }
      List<String> observed = tables(connection);
      Set<String> expected = new TreeSet<>(originalTables);
      if (sourceName.endsWith("product"))
        expected.addAll(
            List.of(
                "product_metric_control",
                "product_online_stat",
                "product_metric_applied_event",
                "product_metric_outbox",
                "product_metric_projection_outbox",
                "product_metric_quarantine"));
      else expected.add("stock_sales_outbox");
      if (!expected.equals(new TreeSet<>(observed)))
        throw new SQLException("TABLE_INVENTORY_CHANGED");
    }

    private static List<String> tables(Connection connection) throws SQLException {
      List<String> tables = new ArrayList<>();
      try (Statement statement = connection.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()"
                      + " AND table_type='BASE TABLE' ORDER BY table_name")) {
        while (rows.next()) {
          String table = rows.getString(1);
          if (!table.matches("[a-z0-9_]+")) throw new SQLException("TABLE_NAME_INVALID");
          tables.add(table);
        }
      }
      return tables;
    }

    private static Map<String, Object> facts(Connection connection, List<String> tables)
        throws SQLException {
      Map<String, Object> result = new TreeMap<>();
      for (String table : tables) {
        Map<String, Object> fact = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet ddl = statement.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
          ddl.next();
          fact.put("ddl", ddl.getString(2));
        }
        try (Statement statement = connection.createStatement();
            ResultSet indexes = statement.executeQuery("SHOW INDEX FROM `" + table + "`")) {
          fact.put("indexes", cells(indexes));
        }
        try (Statement statement = connection.createStatement();
            ResultSet checksum =
                statement.executeQuery("CHECKSUM TABLE `" + table + "` EXTENDED")) {
          checksum.next();
          fact.put("checksum", checksum.getString(2));
        }
        List<List<String>> rows;
        try (Statement statement = connection.createStatement();
            ResultSet source = statement.executeQuery("SELECT * FROM `" + table + "`")) {
          rows = cells(source);
        }
        rows.sort(
            Comparator.comparing(
                row -> {
                  try {
                    return JSON.writeValueAsString(row);
                  } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                  }
                }));
        fact.put("count", rows.size());
        fact.put("rows", rows);
        result.put(table, fact);
      }
      return result;
    }

    private static List<List<String>> cells(ResultSet result) throws SQLException {
      List<List<String>> rows = new ArrayList<>();
      int count = result.getMetaData().getColumnCount();
      while (result.next()) {
        List<String> row = new ArrayList<>();
        for (int column = 1; column <= count; column++) {
          int type = result.getMetaData().getColumnType(column);
          byte[] bytes;
          if (type == Types.BLOB
              || type == Types.BINARY
              || type == Types.VARBINARY
              || type == Types.LONGVARBINARY) {
            bytes = result.getBytes(column);
            row.add(bytes == null ? null : Base64.getEncoder().encodeToString(bytes));
          } else row.add(result.getString(column));
        }
        rows.add(row);
      }
      return rows;
    }
  }

  /**
   * Raw DriverManager avoids Spring's DEBUG URL logger; every acquired owned connection is counted.
   */
  static final class OwnedDataSource extends AbstractDataSource {
    /** Explicit loopback base is private and never logged. */
    private final String base;

    /** Exact allowed physical catalog. */
    final String catalog;

    /** Independent connection close gate. */
    final AtomicInteger open = new AtomicInteger();

    /** Immutable explicit strategy; old constructors never read the cache system property. */
    final ProductMetricQueryInfoCacheSupport.Admission queryInfoAdmission;

    /** Independent source/admin UUID, never inferred from a controlled raw's self-report. */
    final String expectedServerUuid;

    /** Null for legacy; fixed-space audit for each of the two controlled comparison arms. */
    final ProductMetricQueryInfoCacheSupport.Audit queryInfoAudit;

    /** Actual DriverManager leaf, with an explicit connection-only test seam. */
    private final ProductMetricQueryInfoCacheSupport.RawOpener opener;

    /**
     * Keep the original URL, no flag read and no new audit for independent legacy/Stock callers.
     */
    OwnedDataSource(String base, String catalog) {
      this(
          base,
          catalog,
          ProductMetricQueryInfoCacheSupport.legacy(),
          null,
          DriverManager::getConnection);
    }

    /** Use one already admitted immutable context and the independent instance UUID anchor. */
    OwnedDataSource(
        String base,
        String catalog,
        ProductMetricQueryInfoCacheSupport.Admission admission,
        String expectedServerUuid) {
      this(base, catalog, admission, expectedServerUuid, DriverManager::getConnection);
    }

    /** Inject only the JDBC acquisition leaf in offline tests, never the strategy/audit subject. */
    OwnedDataSource(
        String base,
        String catalog,
        ProductMetricQueryInfoCacheSupport.Admission admission,
        String expectedServerUuid,
        ProductMetricQueryInfoCacheSupport.RawOpener opener) {
      this.base = base;
      this.catalog = catalog;
      this.queryInfoAdmission = ProductMetricQueryInfoCacheSupport.requireIntrinsic(admission);
      this.opener = java.util.Objects.requireNonNull(opener);
      if (admission.controlled() && !catalog.endsWith("_product"))
        throw new IllegalArgumentException("CM21_QUERYINFO_PRODUCT_ONLY");
      this.expectedServerUuid =
          admission.controlled()
              ? ProductMetricQueryInfoCacheSupport.expectedUuid(expectedServerUuid)
              : null;
      this.queryInfoAudit =
          admission.controlled() ? new ProductMetricQueryInfoCacheSupport.Audit() : null;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return getConnection("root", "root");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      if (!"root".equals(username) || !"root".equals(password))
        throw new SQLException("LOCAL_CREDENTIAL_CONTRACT");
      if (queryInfoAdmission.controlled()) {
        ProductMetricQueryInfoCacheSupport.actor("connection", queryInfoAdmission);
        return ProductMetricQueryInfoCacheSupport.open(
            base, catalog, queryInfoAdmission, expectedServerUuid, open, queryInfoAudit, opener);
      }
      Connection raw =
          opener.open(
              base
                  + catalog
                  + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai&connectTimeout=3000&socketTimeout=5000",
              username,
              password);
      if (!catalog.equals(raw.getCatalog()) || !catalog.equals(actualCatalog(raw))) {
        raw.close();
        throw new SQLException("OWNED_CATALOG_MISMATCH");
      }
      try (Statement statement = raw.createStatement()) {
        statement.execute("SET time_zone='+08:00'");
      }
      open.incrementAndGet();
      boolean[] closed = {false};
      return (Connection)
          java.lang.reflect.Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class[] {Connection.class},
              (proxy, method, args) -> {
                try {
                  if (method.getName().equals("close")) {
                    if (!closed[0]) {
                      raw.close();
                      closed[0] = true;
                      open.decrementAndGet();
                    }
                    return null;
                  }
                  return method.invoke(raw, args);
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }
  }

  /**
   * Product has no Seata dependency; absence is explicitly N/A, while a present class is actually
   * queried.
   */
  private static Map<String, Object> optionalRootContextObservation() throws Exception {
    Class<?> rootContext;
    try {
      rootContext =
          Class.forName(
              "io.seata.core.context.RootContext",
              false,
              ProductOnlineMetricsMySqlIT.class.getClassLoader());
    } catch (ClassNotFoundException absent) {
      return Map.of(
          "status",
          "N/A_CLASS_ABSENT",
          "classPresent",
          false,
          "xidChecked",
          false,
          "xidPresent",
          false);
    }
    Object xid = rootContext.getMethod("getXID").invoke(null);
    return Map.of(
        "status",
        xid == null ? "CHECKED_EMPTY" : "CHECKED_ACTIVE",
        "classPresent",
        true,
        "xidChecked",
        true,
        "xidPresent",
        xid != null);
  }

  private static String actualCatalog(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT DATABASE()")) {
      if (!result.next()) throw new SQLException("CURRENT_CATALOG_MISSING");
      return result.getString(1);
    }
  }

  private static void assertSignal(Throwable failure, String marker) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause instanceof SQLException sql
          && "45000".equals(sql.getSQLState())
          && sql.getErrorCode() == 1644
          && sql.getMessage().contains(marker)) return;
    fail("Expected original MySQL SIGNAL proof: " + marker);
  }
}
