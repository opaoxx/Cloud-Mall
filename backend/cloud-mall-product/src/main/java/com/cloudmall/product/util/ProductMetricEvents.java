package com.cloudmall.product.util;

import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/** V1 identities, bounded wire parsing and delivery-independent business fingerprints. */
public final class ProductMetricEvents {
  /** A new online series never modifies the imported historical counters. */
  public static final String SERIES = "cm21-online-v1";

  /** One canonical writer also retains explicit null dimensions. */
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  /** Only these reviewed wire properties are accepted. */
  private static final Set<String> FIELDS =
      Set.of(
          "payloadVersion",
          "seriesId",
          "eventId",
          "eventType",
          "producer",
          "requestId",
          "productId",
          "skuId",
          "quantity",
          "orderNo",
          "sourceRef",
          "sourceRevision",
          "occurredAt",
          "traceId");

  /** Persisted business times have exactly millisecond precision and Shanghai's offset. */
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX");

  private ProductMetricEvents() {}

  /** Build one detail or final-page exposure event using the original server request UUID. */
  public static ProductMetricEventDTO product(
      String requestId, long productId, boolean search, String traceId) {
    ProductMetricEventDTO event = new ProductMetricEventDTO();
    event.payloadVersion = 1;
    event.seriesId = SERIES;
    event.producer = "PRODUCT";
    event.requestId = requestId;
    event.productId = productId;
    event.quantity = 1;
    event.eventType = search ? "PRODUCT_SEARCH_EXPOSED" : "PRODUCT_VIEW_CONFIRMED";
    event.sourceRef = (search ? "product-search:" : "product-detail:") + requestId;
    event.occurredAt = TIME.format(OffsetDateTime.now(ZoneOffset.ofHours(8)));
    event.traceId = traceId;
    event.eventId = identity(event);
    validate(event);
    return event;
  }

  /** Parse a complete bounded JSON envelope before any ledger or counter mutation. */
  public static ProductMetricEventDTO parse(byte[] body) {
    if (body == null || body.length == 0 || body.length > 65536) throw invalid("BODY_SIZE");
    try {
      JsonFactory factory = new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      try (JsonParser parser = factory.createParser(body)) {
        int depth = 0;
        int tokens = 0;
        while (parser.nextToken() != null) {
          if (++tokens > 2000) throw invalid("JSON_TOKEN_LIMIT");
          JsonToken token = parser.currentToken();
          if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
            if (++depth > 8) throw invalid("JSON_DEPTH_LIMIT");
            // V1 has no array dimensions; reject rather than silently skip nested values.
            if (token == JsonToken.START_ARRAY) throw invalid("JSON_ARRAY");
          } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
          if ((token == JsonToken.VALUE_STRING || token == JsonToken.FIELD_NAME)
              && parser.getTextLength() > 2048) throw invalid("JSON_STRING_LIMIT");
        }
      }
      JsonNode tree = JSON.readTree(body);
      if (tree == null || !tree.isObject() || tree.size() != FIELDS.size())
        throw invalid("ENVELOPE_FIELDS");
      tree.fieldNames()
          .forEachRemaining(
              name -> {
                if (!FIELDS.contains(name)) throw invalid("UNKNOWN_FIELD");
              });
      for (String name : Set.of("payloadVersion", "productId", "quantity")) {
        if (!tree.path(name).isIntegralNumber() || !tree.path(name).canConvertToLong())
          throw invalid("INTEGER_TYPE");
      }
      for (String name : Set.of("skuId", "sourceRevision")) {
        JsonNode value = tree.get(name);
        if (!value.isNull() && (!value.isIntegralNumber() || !value.canConvertToLong()))
          throw invalid("INTEGER_TYPE");
      }
      if (!tree.path("quantity").canConvertToInt()
          || !tree.path("payloadVersion").canConvertToInt()) throw invalid("INTEGER_RANGE");
      for (String name : FIELDS) {
        if (Set.of("payloadVersion", "productId", "quantity", "skuId", "sourceRevision")
            .contains(name)) continue;
        if (!tree.get(name).isNull() && !tree.get(name).isTextual()) throw invalid("STRING_TYPE");
      }
      ProductMetricEventDTO event = JSON.treeToValue(tree, ProductMetricEventDTO.class);
      validate(event);
      return event;
    } catch (InvalidEvent failure) {
      throw failure;
    } catch (Exception failure) {
      throw invalid("INVALID_JSON");
    }
  }

  /** Validate identity and every event-specific dimension without trimming business identities. */
  public static void validate(ProductMetricEventDTO event) {
    if (event == null
        || event.payloadVersion != 1
        || !SERIES.equals(event.seriesId)
        || event.productId == null
        || event.productId <= 0
        || event.quantity == null
        || event.quantity <= 0
        || event.eventId == null
        || !event.eventId.matches("[0-9a-f]{64}")) throw invalid("ENVELOPE_VALUE");
    if (event.traceId != null && event.traceId.length() > 128) throw invalid("TRACE_SIZE");
    try {
      OffsetDateTime time = OffsetDateTime.parse(event.occurredAt, TIME);
      if (!ZoneOffset.ofHours(8).equals(time.getOffset())
          || !TIME.format(time).equals(event.occurredAt)) throw invalid("OCCURRED_AT");
    } catch (RuntimeException failure) {
      throw invalid("OCCURRED_AT");
    }
    if ("STOCK_SALE_CONFIRMED".equals(event.eventType)) {
      if (!"STOCK".equals(event.producer)
          || event.requestId != null
          || event.skuId == null
          || event.skuId <= 0
          || event.orderNo == null
          || event.orderNo.isEmpty()
          || event.orderNo.length() > 64
          || event.orderNo.contains(":")
          || event.sourceRevision == null
          || event.sourceRevision < 0
          || event.sourceRef == null
          || event.sourceRef.length() > 160
          || !(event.sourceRef.equals("stock_flow:" + event.orderNo + ":CONFIRM:" + event.skuId)
              || event.sourceRef.equals("seckill_quota_settlement:" + event.orderNo)))
        throw invalid("SALE_DIMENSIONS");
    } else {
      boolean search = "PRODUCT_SEARCH_EXPOSED".equals(event.eventType);
      if ((!search && !"PRODUCT_VIEW_CONFIRMED".equals(event.eventType))
          || !"PRODUCT".equals(event.producer)
          || event.requestId == null
          || !canonicalUuid(event.requestId)
          || event.quantity != 1
          || event.skuId != null
          || event.orderNo != null
          || event.sourceRevision != null
          || !(search ? "product-search:" : "product-detail:")
              .concat(event.requestId)
              .equals(event.sourceRef)) throw invalid("PRODUCT_DIMENSIONS");
    }
    if ("STOCK_SALE_CONFIRMED".equals(event.eventType)
        && event.sourceRef.startsWith("seckill_quota_settlement:")
        && event.quantity != 1) throw invalid("SECKILL_QUANTITY");
    if (!identity(event).equals(event.eventId)) throw invalid("EVENT_ID");
  }

  /** Stable event identity excludes arrival, retry, source kind and transport correlation. */
  public static String identity(ProductMetricEventDTO event) {
    String input =
        "STOCK_SALE_CONFIRMED".equals(event.eventType)
            ? "CM21:1:STOCK:CONFIRM:" + event.orderNo + ":" + event.skuId
            : "CM21:1:PRODUCT:"
                + ("PRODUCT_SEARCH_EXPOSED".equals(event.eventType) ? "SEARCH:" : "VIEW:")
                + event.requestId
                + ":"
                + event.productId;
    return sha256(input.getBytes(StandardCharsets.UTF_8));
  }

  /** Fingerprint all business dimensions in fixed order; trace is deliberately excluded. */
  public static String fingerprint(ProductMetricEventDTO event) {
    LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
    fields.put("payloadVersion", event.payloadVersion);
    fields.put("seriesId", event.seriesId);
    fields.put("eventId", event.eventId);
    fields.put("eventType", event.eventType);
    fields.put("producer", event.producer);
    fields.put("requestId", event.requestId);
    fields.put("productId", event.productId);
    fields.put("skuId", event.skuId);
    fields.put("quantity", event.quantity);
    fields.put("orderNo", event.orderNo);
    fields.put("sourceRef", event.sourceRef);
    fields.put("sourceRevision", event.sourceRevision);
    fields.put("occurredAt", event.occurredAt);
    try {
      return sha256(JSON.writeValueAsBytes(fields));
    } catch (Exception failure) {
      throw new IllegalStateException("METRIC_CANONICAL_JSON", failure);
    }
  }

  /** Serialize once before insertion; publishers use the stored bytes without regeneration. */
  public static byte[] bytes(ProductMetricEventDTO event) {
    validate(event);
    try {
      return JSON.writeValueAsBytes(event);
    } catch (Exception failure) {
      throw new IllegalStateException("METRIC_WIRE_JSON", failure);
    }
  }

  /** Lowercase SHA-256 also safely identifies quarantined untrusted bytes. */
  public static String sha256(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static boolean canonicalUuid(String value) {
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException failure) {
      return false;
    }
  }

  private static InvalidEvent invalid(String reason) {
    return new InvalidEvent(reason);
  }

  /** Permanent safe classification used by the durable quarantine path. */
  public static final class InvalidEvent extends RuntimeException {
    /** Fixed reason code, never untrusted payload text. */
    private final String reason;

    /** Construct a permanent envelope rejection. */
    public InvalidEvent(String reason) {
      super(reason);
      this.reason = reason;
    }

    /** Return the reviewed safe classification. */
    public String reason() {
      return reason;
    }
  }

  /** Canonical content axis includes history and nulls; it excludes every online metric field. */
  public static String contentFingerprint(
      com.cloudmall.product.domain.po.ProductIndexDocument document) {
    LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
    fields.put("id", document.id());
    fields.put("categoryId", document.categoryId());
    fields.put("name", document.name());
    fields.put("mainImage", document.mainImage());
    fields.put("description", document.description());
    fields.put("price", document.price().setScale(2).toPlainString());
    fields.put("status", document.status());
    fields.put("productVersion", document.version());
    fields.put(
        "updatedAt",
        document
            .updatedAt()
            .atZone(java.time.ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli());
    fields.put("viewCount", document.viewCount());
    fields.put("searchCount", document.searchCount());
    fields.put("hotScore", document.hotScore().setScale(6).toPlainString());
    fields.put("metricSourceType", document.metricSourceType());
    fields.put("metricSourceRef", document.metricSourceRef());
    fields.put("published", document.status() == 1);
    fields.put("visible", document.status() == 1);
    fields.put("tombstone", false);
    return hashFields(fields);
  }

  /** Canonical metric axis contains only the fixed series, product, exact counters and version. */
  public static String metricFingerprint(com.cloudmall.product.domain.po.ProductOnlineStatPO stat) {
    LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
    fields.put("onlineSeriesId", SERIES);
    fields.put("id", stat.productId);
    fields.put("onlineViewCount", stat.viewCount);
    fields.put("onlineSearchCount", stat.searchCount);
    fields.put("onlineSoldCount", stat.soldCount);
    fields.put("onlineHotScore", stat.hotScore);
    fields.put("metricVersion", stat.metricVersion);
    return hashFields(fields);
  }

  private static String hashFields(LinkedHashMap<String, Object> fields) {
    try {
      return sha256(JSON.writeValueAsBytes(fields));
    } catch (Exception failure) {
      throw new IllegalStateException("METRIC_FINGERPRINT_JSON", failure);
    }
  }
}
