package com.cloudmall.pay.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PayController {
  private final OrderClient orders;
  private final UserClient users;
  private final JdbcTemplate db;

  @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}")
  private String callbackToken;

  public PayController(OrderClient orders, UserClient users, JdbcTemplate db) {
    this.orders = orders;
    this.users = users;
    this.db = db;
  }

  @Transactional
  @PostMapping
  public synchronized ApiResponse<?> create(
      @RequestHeader("Idempotency-Key") String key, @RequestBody Request req) {
    long uid = AuthContext.requireUserId();
    if (key == null
        || key.isBlank()
        || req == null
        || req.orderNo == null
        || req.payAmount == null
        || req.payAmount.signum() < 0) {
      throw new BizException(ErrorCodes.INVALID, "支付参数不完整", 400);
    }
    OrderClient.OrderView order = order(req.orderNo, uid);
    if (!Objects.equals(uid, order.userId()))
      throw new BizException(ErrorCodes.FORBIDDEN, "无权支付此订单", 403);
    if (!"PENDING_PAYMENT".equals(order.status()))
      throw new BizException(ErrorCodes.PAY_DONE, "订单不处于待支付状态", 409);
    if (req.payAmount.compareTo(order.payAmount()) != 0)
      throw new BizException(ErrorCodes.PAY_MISMATCH, "支付金额与订单应付金额不一致", 409);
    String payNo = payNoFor(uid, key);
    List<Pay> keyed = findBy("pay_no", payNo);
    if (!keyed.isEmpty()) {
      Pay p = keyed.get(0);
      if (!Objects.equals(p.orderNo, req.orderNo)
          || !Objects.equals(p.userId, uid)
          || p.amount.compareTo(req.payAmount) != 0) {
        throw new BizException(ErrorCodes.DUPLICATE, "Idempotency-Key已用于其他支付请求", 409);
      }
      return ApiResponse.ok(p);
    }
    List<Pay> old = findBy("order_no", req.orderNo);
    if (!old.isEmpty()) return ApiResponse.ok(old.get(0));
    OffsetDateTime now = now();
    db.update(
        "insert into pay_record(id,pay_no,order_no,user_id,amount,status,created_at,updated_at)"
            + " values(?,?,?,?,?,'PENDING',?,?)",
        id(),
        payNo,
        req.orderNo,
        uid,
        req.payAmount,
        ts(now),
        ts(now));
    return ApiResponse.ok(find(payNo));
  }

  @GetMapping("/orders/{orderNo}")
  public ApiResponse<?> get(@PathVariable String orderNo) {
    long uid = AuthContext.requireUserId();
    List<Pay> records =
        db.query(
            "select pay_no,order_no,user_id,amount,status,paid_at from pay_record where order_no=?"
                + " and user_id=?",
            (r, n) -> pay(r),
            orderNo,
            uid);
    if (records.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "支付记录不存在", 404);
    return ApiResponse.ok(records.get(0));
  }

  @Transactional
  @PostMapping("/{payNo}/mock-success")
  public synchronized ApiResponse<?> success(@PathVariable String payNo) {
    Pay p = ownedForUpdate(payNo);
    if ("SUCCESS".equals(p.status)) return ApiResponse.ok(p);
    if (!"PENDING".equals(p.status)) throw new BizException(ErrorCodes.PAY_DONE, "支付已处理", 409);
    completeSuccess(p);
    db.update(
        "update pay_record set status='SUCCESS',paid_at=?,updated_at=? where pay_no=? and"
            + " status='PENDING'",
        ts(now()),
        ts(now()),
        payNo);
    return ApiResponse.ok(find(payNo));
  }

  @Transactional
  @PostMapping("/{payNo}/mock-fail")
  public ApiResponse<?> fail(@PathVariable String payNo) {
    Pay p = owned(payNo);
    if ("SUCCESS".equals(p.status)) throw new BizException(ErrorCodes.PAY_DONE, "支付已成功", 409);
    notifyFailure(p);
    if (db.update(
            "update pay_record set status='FAILED',updated_at=? where pay_no=? and"
                + " status='PENDING'",
            ts(now()),
            payNo)
        != 1) {
      throw new BizException(ErrorCodes.PAY_DONE, "支付状态已被处理", 409);
    }
    return ApiResponse.ok(find(payNo));
  }

  @Transactional
  @PostMapping("/callback")
  public synchronized ApiResponse<?> callback(
      @RequestHeader(value = "X-Internal-Callback-Token", required = false) String token,
      @RequestBody Callback c) {
    if (!Objects.equals(callbackToken, token))
      throw new BizException(ErrorCodes.FORBIDDEN, "回调凭证无效", 403);
    if (c == null || c.payNo == null || c.payNo.isBlank() || c.amount == null || c.userId == null) {
      throw new BizException(ErrorCodes.INVALID, "回调参数不完整", 400);
    }
    String callbackId = c.callbackId == null || c.callbackId.isBlank() ? c.payNo : c.callbackId;
    if (!db.query(
            "select pay_no from pay_callback_log where callback_id=?",
            (r, n) -> r.getString(1),
            callbackId)
        .isEmpty()) {
      return ApiResponse.ok(find(c.payNo));
    }
    Pay p = ownedForUser(c.payNo, c.userId);
    if (p.amount.compareTo(c.amount) != 0)
      throw new BizException(ErrorCodes.PAY_MISMATCH, "回调金额不一致", 409);
    if ("PENDING".equals(p.status)) {
      if (c.success) completeSuccess(p);
      else notifyFailure(p);
      if (db.update(
              "update pay_record set status=?,paid_at=case when ? then ? else paid_at"
                  + " end,updated_at=? where pay_no=? and status='PENDING'",
              c.success ? "SUCCESS" : "FAILED",
              c.success,
              ts(now()),
              ts(now()),
              p.payNo)
          != 1) {
        throw new BizException(ErrorCodes.PAY_DONE, "支付状态已被处理", 409);
      }
    } else if ("SUCCESS".equals(p.status) && !c.success) {
      throw new BizException(ErrorCodes.PAY_DONE, "支付已成功", 409);
    }
    db.update(
        "insert into"
            + " pay_callback_log(id,pay_no,callback_id,callback_status,payload,processed_at,created_at)"
            + " values(?,?,?,?,?,?,?)",
        id(),
        p.payNo,
        callbackId,
        c.success ? "SUCCESS" : "FAILED",
        "{}",
        ts(now()),
        ts(now()));
    return ApiResponse.ok(find(p.payNo));
  }

  private void completeSuccess(Pay p) {
    ApiResponse<UserClient.BalanceView> debit =
        users.debit(p.userId, p.userId, new UserClient.DebitRequest(p.payNo, p.amount));
    if (debit == null || !"0".equals(debit.code))
      throw new BizException("PAYMENT_FAILED", "余额不足", 409);
    ApiResponse<OrderClient.OrderView> paid = orders.paid(p.orderNo, p.userId);
    if (paid == null || !"0".equals(paid.code))
      throw new BizException(ErrorCodes.INTERNAL, "订单支付状态同步失败", 500);
  }

  private void notifyFailure(Pay p) {
    try {
      ApiResponse<?> response = orders.cancel(p.orderNo, p.userId);
      if (response == null || !"0".equals(response.code))
        throw new BizException(ErrorCodes.INTERNAL, "订单失败回滚未确认", 500);
    } catch (BizException e) {
      throw e;
    } catch (Exception e) {
      throw new BizException(ErrorCodes.INTERNAL, "订单失败回滚失败", 500);
    }
  }

  private OrderClient.OrderView order(String no, long uid) {
    ApiResponse<OrderClient.OrderView> response = orders.get(no, uid);
    if (response == null || response.data == null)
      throw new BizException(ErrorCodes.NOT_FOUND, "订单不存在", 404);
    return response.data;
  }

  private Pay owned(String no) {
    return ownedForUser(no, AuthContext.requireUserId());
  }

  private Pay ownedForUpdate(String no) {
    List<Pay> records =
        db.query(
            "select pay_no,order_no,user_id,amount,status,paid_at from pay_record where pay_no=?"
                + " for update",
            (r, n) -> pay(r),
            no);
    if (records.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "支付记录不存在", 404);
    Pay p = records.get(0);
    if (!Objects.equals(p.userId, AuthContext.requireUserId()))
      throw new BizException(ErrorCodes.FORBIDDEN, "无权访问此支付记录", 403);
    return p;
  }

  private Pay ownedForUser(String no, long userId) {
    Pay p = find(no);
    if (!Objects.equals(p.userId, userId))
      throw new BizException(ErrorCodes.FORBIDDEN, "无权访问此支付记录", 403);
    return p;
  }

  private Pay find(String no) {
    List<Pay> records = findBy("pay_no", no);
    if (records.isEmpty()) throw new BizException(ErrorCodes.NOT_FOUND, "支付记录不存在", 404);
    return records.get(0);
  }

  private List<Pay> findBy(String column, String value) {
    return db.query(
        "select pay_no,order_no,user_id,amount,status,paid_at from pay_record where "
            + column
            + "=?",
        (r, n) -> pay(r),
        value);
  }

  private static String payNoFor(long uid, String key) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest((uid + ":" + key).getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte value : hash) result.append(String.format("%02x", value));
      return result.substring(0, 48);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Pay pay(java.sql.ResultSet r) throws java.sql.SQLException {
    Pay p = new Pay();
    p.payNo = r.getString(1);
    p.orderNo = r.getString(2);
    p.userId = r.getLong(3);
    p.amount = r.getBigDecimal(4);
    p.status = r.getString(5);
    if (r.getTimestamp(6) != null)
      p.paidAt = r.getTimestamp(6).toInstant().atOffset(ZoneOffset.ofHours(8)).toString();
    return p;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }

  private static java.sql.Timestamp ts(OffsetDateTime value) {
    return java.sql.Timestamp.from(value.toInstant());
  }

  private static long id() {
    return Math.abs(UUID.randomUUID().getMostSignificantBits());
  }

  public static class Request {
    public String orderNo;
    public BigDecimal payAmount;
  }

  public static class Callback {
    public String payNo;
    public boolean success;
    public String callbackId;
    public BigDecimal amount;
    public Long userId;
  }

  public static class Pay {
    public String payNo, orderNo, status, paidAt;
    public Long userId;
    public BigDecimal amount;
  }
}
