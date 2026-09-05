package com.cloudmall.user;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api")
public class UserController {
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public UserController(JdbcTemplate jdbc, StringRedisTemplate redis) { this.jdbc = jdbc; this.redis = redis; }

    @PostConstruct
    public void ensureLocalAdmin() {
        Integer count = jdbc.queryForObject("select count(*) from mall_user where username = ?", Integer.class, "admin");
        if (count != null && count == 0) {
            OffsetDateTime now = OffsetDateTime.now();
            jdbc.update("insert into mall_user(id,username,password_hash,role,balance,status,created_at,updated_at) values(?,?,?,?,?,?,?,?)",
                    System.currentTimeMillis(), "admin", ENCODER.encode("admin"), "ADMIN", new BigDecimal("10000.00"), 1, now, now);
        }
    }

    @PostMapping("/auth/register")
    public ApiResponse<?> register(@Valid @RequestBody Credentials c) {
        Integer count = jdbc.queryForObject("select count(*) from mall_user where username = ?", Integer.class, c.username);
        if (count != null && count > 0) throw new BizException("USER_DUPLICATE_USERNAME", "用户名已存在", 409);
        long id = System.currentTimeMillis(); OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("insert into mall_user(id,username,password_hash,role,balance,status,created_at,updated_at) values(?,?,?,?,?,?,?,?)",
                id, c.username, ENCODER.encode(c.password), "USER", new BigDecimal("10000.00"), 1, now, now);
        return ApiResponse.ok(Map.of("userId", id, "username", c.username));
    }

    @PostMapping("/auth/login")
    public ApiResponse<?> login(@Valid @RequestBody Credentials c) {
        List<User> users = jdbc.query("select id,username,password_hash,role,balance,status,nickname,phone,avatar_url from mall_user where username=?",
                (rs, row) -> user(rs), c.username);
        if (users.isEmpty() || !users.get(0).status || !ENCODER.matches(c.password, users.get(0).passwordHash)) throw new BizException("USER_LOGIN_FAILED", "用户名或密码错误", 401);
        User user = users.get(0); String token = UUID.randomUUID().toString();
        redis.opsForValue().set("auth:token:" + token, user.id + ":" + user.role, Duration.ofHours(2));
        return ApiResponse.ok(Map.of("token", token, "user", user.view()));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<?> logout(@RequestHeader(value="Authorization", required=false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) redis.delete("auth:token:" + authorization.substring(7).trim());
        return ApiResponse.ok(null);
    }

    @GetMapping("/users/me") public ApiResponse<?> me() { return ApiResponse.ok(current().view()); }

    @PostMapping("/internal/users/{userId}/balance/debit")
    public synchronized ApiResponse<?> debit(@PathVariable Long userId,
                                              @RequestHeader(value = "X-User-Id", required = false) Long callerId,
                                              @Valid @RequestBody DebitRequest request) {
        if (!Objects.equals(userId, callerId)) throw new BizException(ErrorCodes.FORBIDDEN, "内部用户身份无效", 403);
        if (request == null || request.paymentKey == null || request.paymentKey.isBlank()
                || request.amount == null || request.amount.signum() <= 0) {
            throw new BizException(ErrorCodes.INVALID, "扣款参数不完整", 400);
        }
        String resultKey = "balance:debit:" + userId + ":" + request.paymentKey;
        String previous = redis.opsForValue().get(resultKey);
        if (previous != null) return ApiResponse.ok(Map.of("balance", previous));
        int changed = jdbc.update("update mall_user set balance=balance-?,updated_at=? where id=? and status=1 and balance>=?",
                request.amount, OffsetDateTime.now(), userId, request.amount);
        if (changed != 1) throw new BizException("PAYMENT_FAILED", "余额不足", 409);
        BigDecimal balance = jdbc.queryForObject("select balance from mall_user where id=?", BigDecimal.class, userId);
        redis.opsForValue().set(resultKey, balance.toPlainString(), Duration.ofDays(30));
        return ApiResponse.ok(Map.of("balance", balance.toPlainString()));
    }

    @PutMapping("/users/me")
    public ApiResponse<?> update(@RequestBody Profile p) {
        jdbc.update("update mall_user set nickname=?,phone=?,avatar_url=?,updated_at=? where id=?", p.nickname, p.phone, p.avatarUrl, OffsetDateTime.now(), AuthContext.requireUserId());
        return me();
    }

    @GetMapping("/users/me/addresses")
    public ApiResponse<?> addresses() {
        return ApiResponse.ok(jdbc.query("select id,receiver_name,receiver_phone,region_detail,is_default from user_address where user_id=? order by is_default desc,id desc",
                (rs, row) -> address(rs.getLong("id"), rs.getString("receiver_name"), rs.getString("receiver_phone"), rs.getString("region_detail"), rs.getBoolean("is_default")), AuthContext.requireUserId()));
    }

    @PostMapping("/users/me/addresses")
    public ApiResponse<?> addAddress(@RequestBody AddressRequest r) {
        Long userId = AuthContext.requireUserId(); long id = System.currentTimeMillis(); OffsetDateTime now = OffsetDateTime.now();
        if (r.isDefault) jdbc.update("update user_address set is_default=0 where user_id=?", userId);
        jdbc.update("insert into user_address(id,user_id,receiver_name,receiver_phone,region_detail,is_default,created_at,updated_at) values(?,?,?,?,?,?,?,?)",
                id, userId, r.receiver, r.phone, r.detailAddress, r.isDefault, now, now);
        return ApiResponse.ok(address(id, r.receiver, r.phone, r.detailAddress, r.isDefault));
    }

    @PutMapping("/users/me/addresses/{id}")
    public ApiResponse<?> updateAddress(@PathVariable Long id, @RequestBody AddressRequest r) {
        Long userId = AuthContext.requireUserId();
        int changed = jdbc.update("update user_address set receiver_name=?,receiver_phone=?,region_detail=?,is_default=?,updated_at=? where id=? and user_id=?",
                r.receiver, r.phone, r.detailAddress, r.isDefault, OffsetDateTime.now(), id, userId);
        if (changed == 0) throw new BizException(ErrorCodes.NOT_FOUND, "地址不存在", 404);
        if (r.isDefault) jdbc.update("update user_address set is_default=0 where user_id=? and id<>?", userId, id);
        return ApiResponse.ok(address(id, r.receiver, r.phone, r.detailAddress, r.isDefault));
    }

    @DeleteMapping("/users/me/addresses/{id}")
    public ApiResponse<?> deleteAddress(@PathVariable Long id) {
        if (jdbc.update("delete from user_address where id=? and user_id=?", id, AuthContext.requireUserId()) == 0) throw new BizException(ErrorCodes.NOT_FOUND, "地址不存在", 404);
        return ApiResponse.ok(null);
    }

    private User current() {
        return jdbc.queryForObject("select id,username,password_hash,role,balance,status,nickname,phone,avatar_url from mall_user where id=?",
                (rs, row) -> user(rs), AuthContext.requireUserId());
    }
    private static User user(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new User(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"), rs.getString("role"),
                rs.getBigDecimal("balance"), rs.getInt("status") == 1, rs.getString("nickname"), rs.getString("phone"), rs.getString("avatar_url"));
    }
    private static Map<String,Object> address(long id,String receiver,String phone,String detail,boolean isDefault){return Map.of("id",id,"receiver",receiver,"phone",phone,"detailAddress",detail,"isDefault",isDefault);}
    public static class Credentials { @NotBlank public String username; @NotBlank public String password; }
    public static class Profile { public String nickname; public String phone; public String avatarUrl; }
    public static class DebitRequest { @NotBlank public String paymentKey; public BigDecimal amount; }
    public static class AddressRequest { public String receiver; public String phone; public String detailAddress; public boolean isDefault; }
    private record User(Long id,String username,String passwordHash,String role,BigDecimal balance,boolean status,String nickname,String phone,String avatarUrl){Map<String,Object> view(){return Map.of("userId",id,"username",username,"role",role,"roles",List.of(role),"nickname",nickname==null?"":nickname,"phone",phone==null?"":phone,"avatarUrl",avatarUrl==null?"":avatarUrl,"balance",balance.toPlainString());}}
}
