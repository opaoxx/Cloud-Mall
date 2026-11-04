package com.cloudmall.stock.service.impl.metrics;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.dto.StockSalesEventDTO;
import com.cloudmall.stock.domain.po.StockSalesOutboxPO;
import com.cloudmall.stock.mapper.StockSalesOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Creates sale events only after the original first confirmation has changed durable business
 * facts.
 */
@Service
@ConditionalOnProperty(name = "cloudmall.stock.sales-metrics.enabled", havingValue = "true")
public class StockSalesOutboxService {
  /** SQL resource shared with confirmation, flow and settlement rows. */
  private final StockSalesOutboxMapper mapper;

  /** Canonical V1 JSON writer; explicit null source dimensions are retained. */
  private final ObjectMapper json = new ObjectMapper();

  /** Construct the transaction participant, not an independent confirmation service. */
  public StockSalesOutboxService(StockSalesOutboxMapper mapper) {
    this.mapper = mapper;
  }

  /** Verify raw flow proof and append the normal sale atomically after its stock/flow mutations. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void normal(
      String orderNo, long skuId, int quantity, long productId, long revision, String traceId) {
    // 1. Refuse distributed AT ambiguity before recording a local-only metric assertion.
    requireLocal();
    // 2. Require exact original identities and one first CONFIRM proof, never CI aliases.
    long reserved = 0;
    int confirms = 0;
    String key = orderNo + ":CONFIRM:" + skuId;
    for (Map<String, Object> flow : mapper.flowProof(orderNo, skuId)) {
      if (!orderNo.equals(flow.get("order_no"))
          || ((Number) flow.get("sku_id")).longValue() != skuId) throw conflict();
      String type = String.valueOf(flow.get("flow_type"));
      int amount = ((Number) flow.get("quantity")).intValue();
      if (amount <= 0) throw conflict();
      if ("RESERVE".equals(type)) reserved = Math.addExact(reserved, amount);
      else if ("CONFIRM".equals(type)) {
        if (!key.equals(flow.get("idempotency_key")) || amount != quantity) throw conflict();
        confirms++;
      } else throw conflict();
    }
    if (reserved != quantity || confirms != 1) throw conflict();
    // 3. Persist one immutable event in the original local transaction; no RPC or independent
    // commit.
    append(orderNo, skuId, quantity, productId, revision, "stock_flow:" + key, traceId);
  }

  /** Append only a successful original seckill CAS using its re-read persisted revision. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void seckill(
      String orderNo, long skuId, long productId, long actualRevision, String traceId) {
    // 1. The caller holds the original settlement and SKU locks.
    requireLocal();
    // 2. Quantity is exactly the original accepted seckill quantity, one.
    // 3. The identity intentionally shares the normal sale namespace.
    append(
        orderNo,
        skuId,
        1,
        productId,
        actualRevision,
        "seckill_quota_settlement:" + orderNo,
        traceId);
  }

  /** Batch claiming is a real short proxy transaction and ends before broker publication. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
  public List<StockSalesOutboxPO> claim(String owner) {
    return mapper.claim(owner, now());
  }

  /** Record an acknowledged or uncertain publication in a new bounded local transaction. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
  public int finish(String id, String owner, boolean success, String error) {
    int changed = mapper.finish(id, owner, success, error, now());
    if (changed != 1) throw new IllegalStateException("METRIC_FINISH_OWNER_LOST");
    return changed;
  }

  private void append(
      String orderNo,
      long skuId,
      int quantity,
      long productId,
      long revision,
      String source,
      String traceId) {
    if (orderNo == null
        || orderNo.isEmpty()
        || orderNo.length() > 64
        || orderNo.contains(":")
        || skuId <= 0
        || productId <= 0
        || quantity <= 0
        || revision < 0) throw unavailable(null);
    StockSalesEventDTO event = new StockSalesEventDTO();
    event.payloadVersion = 1;
    event.seriesId = "cm21-online-v1";
    event.eventType = "STOCK_SALE_CONFIRMED";
    event.producer = "STOCK";
    event.productId = productId;
    event.skuId = skuId;
    event.quantity = quantity;
    event.orderNo = orderNo;
    event.sourceRef = source;
    event.sourceRevision = revision;
    event.eventId = sha("CM21:1:STOCK:CONFIRM:" + orderNo + ":" + skuId);
    event.occurredAt =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX")
            .format(OffsetDateTime.now(ZoneOffset.ofHours(8)));
    event.traceId = traceId != null && traceId.length() <= 128 ? traceId : null;
    LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("payloadVersion", 1);
    canonical.put("seriesId", event.seriesId);
    canonical.put("eventId", event.eventId);
    canonical.put("eventType", event.eventType);
    canonical.put("producer", event.producer);
    canonical.put("requestId", null);
    canonical.put("productId", productId);
    canonical.put("skuId", skuId);
    canonical.put("quantity", quantity);
    canonical.put("orderNo", orderNo);
    canonical.put("sourceRef", source);
    canonical.put("sourceRevision", revision);
    canonical.put("occurredAt", event.occurredAt);
    try {
      String fingerprint = sha(json.writeValueAsString(canonical));
      mapper.insert(event, fingerprint, json.writeValueAsString(event), now());
    } catch (org.springframework.dao.DataAccessException failure) {
      throw unavailable(failure);
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw unavailable(failure);
    }
  }

  private static void requireLocal() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || RootContext.inGlobalTransaction()) throw unavailable(null);
  }

  private static BizException conflict() {
    return new BizException(
        "STOCK_SALES_EVENT_CONFLICT", "Sale source identity conflicts with its confirmation", 409);
  }

  /** Create the fixed safe 503 while retaining the database cause for local diagnosis. */
  public static BizException unavailable(Throwable cause) {
    BizException failure =
        new BizException("STOCK_SALES_EVENT_UNAVAILABLE", "Sale event could not be committed", 503);
    if (cause != null) failure.initCause(cause);
    return failure;
  }

  private static String sha(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MILLIS);
  }
}
