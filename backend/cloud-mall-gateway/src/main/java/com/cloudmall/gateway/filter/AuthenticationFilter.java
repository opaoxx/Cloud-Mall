package com.cloudmall.gateway.filter;

import com.cloudmall.gateway.util.GatewayAccessPolicy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 在路由转发前核验 Redis 不透明 Token，并使用原身份覆盖下游用户请求头。 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {
  /** 异步读取 auth:token: 登录会话的 Redis 客户端。 */
  private final ReactiveStringRedisTemplate redisTemplate;

  /** 创建认证过滤器并注入响应式会话存储客户端。 */
  public AuthenticationFilter(ReactiveStringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  /** 鉴权先于 Sentinel 过滤器执行，避免无 Token 请求占用秒杀配额。 */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  /** 按原拦截先后顺序核验访问资格、读取 Token 并转发认证身份。 */
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String path = exchange.getRequest().getURI().getPath();
    // 1. 先隔离内部 TCC 和库存请求，再处理允许匿名访问的路径。
    if (GatewayAccessPolicy.isInternalTccPath(path)) {
      exchange.getResponse().getHeaders().setCacheControl("no-store");
      return error(exchange, HttpStatus.FORBIDDEN, "COMMON_FORBIDDEN", "内部接口不允许通过网关访问");
    }
    if (path.startsWith("/api/stock/")) return unauthorized(exchange);
    if (GatewayAccessPolicy.isPublicRequest(exchange.getRequest().getMethod(), path))
      return chain.filter(exchange);
    // 2. 保留 Bearer 文本规则，只在订阅时读取 Redis，会话查找失败映射为503。
    String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (authorization == null || !authorization.startsWith("Bearer "))
      return unauthorized(exchange);
    String token = authorization.substring("Bearer ".length()).trim();
    if (token.isEmpty()) return unauthorized(exchange);
    Mono<TokenLookupResult> lookup =
        Mono.defer(() -> redisTemplate.opsForValue().get("auth:token:" + token))
            .map(value -> new TokenLookupResult(value, false))
            .defaultIfEmpty(new TokenLookupResult("", false))
            .onErrorReturn(new TokenLookupResult("", true));
    // 3. 只覆盖认证身份头，下游完成或异常保留其原结果，不重复转发。
    return lookup.flatMap(
        lookupResult -> {
          if (lookupResult.unavailable()) return unavailable(exchange);
          String[] tokenFields = lookupResult.value().split(":", 2);
          if (tokenFields.length != 2) return unauthorized(exchange);
          ServerWebExchange forwarded =
              exchange
                  .mutate()
                  .request(
                      builder ->
                          builder.headers(
                              headers -> {
                                headers.remove("X-User-Id");
                                headers.remove("X-User-Role");
                                headers.add("X-User-Id", tokenFields[0]);
                                headers.add("X-User-Role", tokenFields[1]);
                              }))
                  .build();
          return chain.filter(forwarded);
        });
  }

  /** 对缺失或无法识别的会话返回原401错误体。 */
  private Mono<Void> unauthorized(ServerWebExchange exchange) {
    return error(exchange, HttpStatus.UNAUTHORIZED, "COMMON_UNAUTHORIZED", "请先登录");
  }

  /** Redis 查令牌失败时拒绝转发，故障响应禁止缓存。 */
  private Mono<Void> unavailable(ServerWebExchange exchange) {
    exchange.getResponse().getHeaders().setCacheControl("no-store");
    return error(
        exchange, HttpStatus.SERVICE_UNAVAILABLE, "REDIS_SERVICE_UNAVAILABLE", "依赖服务暂时不可用，请稍后重试");
  }

  /** 按既有 ApiResponse 字段写入固定错误，不回传底层异常细节。 */
  private Mono<Void> error(
      ServerWebExchange exchange, HttpStatus status, String code, String message) {
    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    String json =
        "{\"code\":\""
            + code
            + "\",\"message\":\""
            + message
            + "\",\"data\":null,\"requestId\":\""
            + UUID.randomUUID()
            + "\"}";
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    return exchange
        .getResponse()
        .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
  }

  /**
   * 本次会话查找结果。
   *
   * @param value 原会话文本，缺失会话或查找失败时为空字符串
   * @param unavailable Redis 查找是否发生依赖故障
   */
  private record TokenLookupResult(String value, boolean unavailable) {}
}
