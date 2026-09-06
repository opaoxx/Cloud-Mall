package com.cloudmall.stock.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stock")
public class StockController {
  private static final String PREFIX = "stock:available:", RES = "stock:reservation:";
  private static final String RESERVE =
      "if redis.call('EXISTS',KEYS[1])==1 then return 2 end; for i=2,#KEYS-1 do if"
          + " tonumber(redis.call('GET',KEYS[i]) or '0') < tonumber(ARGV[(i-2)*2+2]) then return 0"
          + " end end; for i=2,#KEYS-1 do redis.call('DECRBY',KEYS[i],ARGV[(i-2)*2+2]);"
          + " redis.call('HSET',KEYS[#KEYS],ARGV[(i-2)*2+1],ARGV[(i-2)*2+2]) end;"
          + " redis.call('SET',KEYS[1],'RESERVED','EX',86400); return 1";
  private static final String ROLLBACK =
      "if redis.call('EXISTS',KEYS[1])==0 then return 0 end; for i=2,#KEYS do"
          + " redis.call('INCRBY',KEYS[i],ARGV[i-1]) end; redis.call('DEL',KEYS[1]); return 1";
  private static final String SECKILL =
      "if redis.call('EXISTS',KEYS[3])==1 then return 3 end; local now=tonumber(ARGV[3]); local"
          + " start=tonumber(ARGV[4]); local finish=tonumber(ARGV[5]); if now<start then return 4"
          + " end; if now>=finish then return 5 end; local used=tonumber(redis.call('GET',KEYS[2])"
          + " or '0'); if used>=tonumber(ARGV[2]) then return 2 end; local"
          + " n=tonumber(redis.call('GET',KEYS[1]) or '0'); if n<1 then return 0 end;"
          + " redis.call('DECR',KEYS[1]); redis.call('INCRBY',KEYS[2],1);"
          + " redis.call('EXPIRE',KEYS[2],ARGV[6]); redis.call('SET',KEYS[3],ARGV[1],'EX',ARGV[6]);"
          + " return 1";
  private static final String SECKILL_ROLLBACK =
      "if redis.call('EXISTS',KEYS[3])==0 then return 0 end; redis.call('INCRBY',KEYS[1],1); local"
          + " used=tonumber(redis.call('GET',KEYS[2]) or '0'); if used<=1 then"
          + " redis.call('DEL',KEYS[2]) else redis.call('DECR',KEYS[2]) end;"
          + " redis.call('DEL',KEYS[3]); return 1";
  private final StringRedisTemplate redis;
  private final JdbcTemplate db;
  private final RabbitTemplate rabbit;
  private final ObjectMapper mapper;
  private final DefaultRedisScript<Long>
      reserveScript = new DefaultRedisScript<>(RESERVE, Long.class),
      rollbackScript = new DefaultRedisScript<>(ROLLBACK, Long.class),
      seckillScript = new DefaultRedisScript<>(SECKILL, Long.class),
      seckillRollbackScript = new DefaultRedisScript<>(SECKILL_ROLLBACK, Long.class);

  public StockController(
      StringRedisTemplate redis, JdbcTemplate db, RabbitTemplate rabbit, ObjectMapper mapper) {
    this.redis = redis;
    this.db = db;
    this.rabbit = rabbit;
    this.mapper = mapper;
  }

  @GetMapping("/skus/{skuId}")
  public ApiResponse<?> get(@PathVariable("skuId") Long skuId) {
    return ApiResponse.ok(Map.of("skuId", skuId, "availableQuantity", quantity(skuId)));
  }

  @Transactional
  @PostMapping("/reservations")
  public ApiResponse<?> reserve(@RequestBody Reservation r) {
    validate(r);
    List<Line> lines = merge(r.items);
    String key = RES + r.orderNo;
    List<String> keys = new ArrayList<>(List.of(key));
    keys.addAll(lines.stream().map(x -> PREFIX + x.skuId).toList());
    keys.add(key + ":lines");
    List<String> args = new ArrayList<>();
    for (Line x : lines) {
      args.add(String.valueOf(x.skuId));
      args.add(String.valueOf(x.quantity));
    }
    Long result = redis.execute(reserveScript, keys, args.toArray());
    if (Objects.equals(result, 0L)) throw new BizException(ErrorCodes.STOCK, "库存不足", 409);
    if (Objects.equals(result, 2L))
      return ApiResponse.ok(Map.of("reserved", true, "idempotent", true));
    try {
      for (Line x : lines) {
        int changed =
            db.update(
                "update stock_sku set"
                    + " available_quantity=available_quantity-?,reserved_quantity=reserved_quantity+?,version=version+1,updated_at=?"
                    + " where sku_id=? and available_quantity>=?",
                x.quantity,
                x.quantity,
                now(),
                x.skuId,
                x.quantity);
        if (changed != 1) throw new BizException(ErrorCodes.STOCK, "库存不足", 409);
        flow(x, r.orderNo, "RESERVE", r.orderNo + ":RESERVE:" + x.skuId);
      }
    } catch (RuntimeException e) {
      compensateReservation(key, lines);
      throw e;
    }
    return ApiResponse.ok(Map.of("reserved", true, "idempotent", false));
  }

  @Transactional
  @PostMapping("/reservations/{orderNo}/confirm")
  public ApiResponse<?> confirm(@PathVariable("orderNo") String orderNo) {
    Map<Object, Object> lines = redis.opsForHash().entries(RES + orderNo + ":lines");
    try {
      for (Map.Entry<Object, Object> e : lines.entrySet()) {
        long sku = Long.parseLong(String.valueOf(e.getKey()));
        int q = Integer.parseInt(String.valueOf(e.getValue()));
        String k = orderNo + ":CONFIRM:" + sku;
        if (!existsFlow(k)) {
          int changed =
              db.update(
                  "update stock_sku set"
                      + " reserved_quantity=reserved_quantity-?,sold_quantity=sold_quantity+?,version=version+1,updated_at=?"
                      + " where sku_id=? and reserved_quantity>=?",
                  q,
                  q,
                  now(),
                  sku,
                  q);
          if (changed != 1) throw new BizException(ErrorCodes.STOCK, "预扣库存不存在或不足", 409);
          flow(new Line(sku, q), orderNo, "CONFIRM", k);
        }
      }
    } catch (RuntimeException e) {
      throw e;
    }
    redis.delete(RES + orderNo);
    redis.delete(RES + orderNo + ":lines");
    return ApiResponse.ok(Map.of("confirmed", true, "idempotent", lines.isEmpty()));
  }

  @Transactional
  @PostMapping("/reservations/{orderNo}/rollback")
  public ApiResponse<?> rollback(@PathVariable("orderNo") String orderNo) {
    Map<Object, Object> lines = redis.opsForHash().entries(RES + orderNo + ":lines");
    for (Map.Entry<Object, Object> e : lines.entrySet()) {
      long sku = Long.parseLong(String.valueOf(e.getKey()));
      int q = Integer.parseInt(String.valueOf(e.getValue()));
      String k = orderNo + ":ROLLBACK:" + sku;
      if (!existsFlow(k)) {
        int changed =
            db.update(
                "update stock_sku set"
                    + " reserved_quantity=reserved_quantity-?,available_quantity=available_quantity+?,version=version+1,updated_at=?"
                    + " where sku_id=? and reserved_quantity>=?",
                q,
                q,
                now(),
                sku,
                q);
        if (changed != 1) throw new BizException(ErrorCodes.STOCK, "预扣库存不存在或不足", 409);
        flow(new Line(sku, q), orderNo, "ROLLBACK", k);
      }
    }
    redis.delete(RES + orderNo);
    redis.delete(RES + orderNo + ":lines");
    return ApiResponse.ok(Map.of("rolledBack", true, "idempotent", lines.isEmpty()));
  }

  @PostMapping("/seckill/reservations")
  public ApiResponse<?> seckill(@RequestBody Map<String, Object> b) {
    long uid = AuthContext.requireUserId();
    String a = String.valueOf(b.get("activityId")),
        sku = String.valueOf(b.get("skuId")),
        user = String.valueOf(b.get("userId")),
        idem = String.valueOf(b.get("idempotencyKey"));
    if ("null".equals(a) || "null".equals(sku) || "null".equals(user) || "null".equals(idem))
      throw new BizException(ErrorCodes.INVALID, "秒杀参数不完整", 400);
    if (!String.valueOf(uid).equals(user))
      throw new BizException(ErrorCodes.FORBIDDEN, "秒杀用户归属校验失败", 403);
    Map<Object, Object> meta = redis.opsForHash().entries("seckill:meta:" + a + ":" + sku);
    long start = epoch(meta.get("startAt")), end = epoch(meta.get("endAt"));
    int limit = Integer.parseInt(String.valueOf(meta.getOrDefault("perUserLimit", "1")));
    long ttl = Math.max(1, (end - System.currentTimeMillis() / 1000));
    String orderKey = "seckill:accepted:" + a + ":" + sku + ":" + user + ":" + idem,
        orderNo =
            now().format(DateTimeFormatter.ofPattern("yyyyMM"))
                + UUID.randomUUID().toString().replace("-", "");
    recordSeckillPending(Long.parseLong(a), Long.parseLong(sku), uid, idem, orderNo);
    List<String> keys =
        List.of("seckill:stock:" + a + ":" + sku, "seckill:user:" + a + ":" + user, orderKey);
    Long result =
        redis.execute(
            seckillScript,
            keys,
            orderNo,
            String.valueOf(limit),
            String.valueOf(System.currentTimeMillis() / 1000),
            String.valueOf(start),
            String.valueOf(end),
            String.valueOf(ttl));
    if (Objects.equals(result, 3L)) {
      String existing = redis.opsForValue().get(orderKey);
      markSeckillAccepted(Long.parseLong(a), Long.parseLong(sku), uid, idem);
      return accepted(existing);
    }
    if (Objects.equals(result, 2L)) {
      markSeckillRejected(Long.parseLong(a), Long.parseLong(sku), uid, idem);
      throw new BizException(ErrorCodes.DUPLICATE, "超过用户限购", 429);
    }
    if (Objects.equals(result, 4L)) {
      markSeckillRejected(Long.parseLong(a), Long.parseLong(sku), uid, idem);
      throw new BizException("SECKILL_NOT_STARTED", "秒杀未开始", 409);
    }
    if (Objects.equals(result, 5L)) {
      markSeckillRejected(Long.parseLong(a), Long.parseLong(sku), uid, idem);
      throw new BizException("SECKILL_ENDED", "秒杀已结束", 409);
    }
    if (Objects.equals(result, 0L)) {
      markSeckillRejected(Long.parseLong(a), Long.parseLong(sku), uid, idem);
      throw new BizException("SECKILL_SOLD_OUT", "秒杀库存耗尽", 409);
    }
    String no = redis.opsForValue().get(orderKey);
    try {
      rabbit.convertAndSend(
          "cloudmall.seckill.order.exchange",
          "",
          Map.of(
              "eventType",
              "SECKILL_ORDER_ACCEPTED",
              "orderNo",
              no,
              "activityId",
              Long.valueOf(a),
              "skuId",
              Long.valueOf(sku),
              "userId",
              uid,
              "idempotencyKey",
              idem,
              "occurredAt",
              now().toString()));
    } catch (Exception e) {
      redis.execute(seckillRollbackScript, keys);
      markSeckillRejected(Long.parseLong(a), Long.parseLong(sku), uid, idem);
      throw new BizException(ErrorCodes.INTERNAL, "秒杀订单入队失败", 500);
    }
    markSeckillAccepted(Long.parseLong(a), Long.parseLong(sku), uid, idem);
    return accepted(no);
  }

  private void recordSeckillPending(
      long activityId, long skuId, long userId, String idempotencyKey, String orderNo) {
    OffsetDateTime now = now();
    db.update(
        "insert into"
            + " seckill_reservation(id,activity_id,sku_id,user_id,order_no,idempotency_key,status,created_at,updated_at)"
            + " values(?,?,?,?,?,?,'PENDING',?,?) on duplicate key update"
            + " order_no=if(status='ACCEPTED',order_no,values(order_no)),status=if(status='ACCEPTED','ACCEPTED','PENDING'),updated_at=values(updated_at)",
        id(),
        activityId,
        skuId,
        userId,
        orderNo,
        idempotencyKey,
        now,
        now);
  }

  private void markSeckillAccepted(
      long activityId, long skuId, long userId, String idempotencyKey) {
    db.update(
        "update seckill_reservation set status='ACCEPTED',updated_at=? where activity_id=? and"
            + " sku_id=? and user_id=? and idempotency_key=?",
        now(),
        activityId,
        skuId,
        userId,
        idempotencyKey);
  }

  private void markSeckillRejected(
      long activityId, long skuId, long userId, String idempotencyKey) {
    db.update(
        "update seckill_reservation set status='REJECTED',updated_at=? where activity_id=? and"
            + " sku_id=? and user_id=? and idempotency_key=? and status='PENDING'",
        now(),
        activityId,
        skuId,
        userId,
        idempotencyKey);
  }

  private ApiResponse<?> accepted(String no) {
    return ApiResponse.ok(Map.of("accepted", true, "orderNo", no, "status", "PENDING_PAYMENT"));
  }

  private boolean existsFlow(String k) {
    return !db.query("select id from stock_flow where idempotency_key=?", (r, n) -> r.getLong(1), k)
        .isEmpty();
  }

  private void flow(Line x, String order, String type, String key) {
    db.update(
        "insert into stock_flow(id,sku_id,order_no,flow_type,quantity,idempotency_key,created_at)"
            + " values(?,?,?,?,?,?,?) on duplicate key update"
            + " idempotency_key=values(idempotency_key)",
        id(),
        x.skuId,
        order,
        type,
        x.quantity,
        key,
        now());
  }

  private void compensateReservation(String key, List<Line> lines) {
    for (Line x : lines) redis.opsForValue().increment(PREFIX + x.skuId, x.quantity);
    redis.delete(key);
    redis.delete(key + ":lines");
  }

  private static long epoch(Object value) {
    try {
      return Instant.parse(String.valueOf(value)).getEpochSecond();
    } catch (Exception e) {
      return 0;
    }
  }

  private int quantity(Long sku) {
    String v = redis.opsForValue().get(PREFIX + sku);
    return v == null ? 0 : Integer.parseInt(v);
  }

  private static List<Line> merge(List<Line> in) {
    Map<Long, Integer> m = new LinkedHashMap<>();
    for (Line x : in) {
      if (x == null || x.skuId == null || x.quantity < 1)
        throw new BizException(ErrorCodes.INVALID, "库存数量必须为正数", 400);
      m.merge(x.skuId, x.quantity, Math::addExact);
    }
    return m.entrySet().stream()
        .map(e -> new Line(e.getKey(), e.getValue()))
        .collect(Collectors.toList());
  }

  private static void validate(Reservation r) {
    if (r == null
        || r.orderNo == null
        || r.orderNo.isBlank()
        || r.items == null
        || r.items.isEmpty()) throw new BizException(ErrorCodes.INVALID, "库存预扣参数不完整", 400);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }

  private static long id() {
    return Math.abs(UUID.randomUUID().getMostSignificantBits());
  }

  public static class Reservation {
    public String orderNo;
    public List<Line> items = new ArrayList<>();
    public String scene;
  }

  public record Line(Long skuId, int quantity) {}
}
