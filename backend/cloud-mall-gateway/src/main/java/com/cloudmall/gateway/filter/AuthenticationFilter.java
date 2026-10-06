package com.cloudmall.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {
  /** 保存 redis 的业务状态或配置。 */
  private final ReactiveStringRedisTemplate redis;

  /** 创建 AuthenticationFilter 实例。 */
  public AuthenticationFilter(ReactiveStringRedisTemplate redis) {
    this.redis = redis;
  }

  /** 鉴权先于 Sentinel 过滤器执行，避免无 Token 请求占用秒杀配额。 */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  /** 执行 filter 相关操作。 */
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String path = exchange.getRequest().getURI().getPath();
    // TCC 桥只能由服务直连。重写前的发现路由前缀、编码和路径参数也不得绕过。
    if (isTccInternalPath(path)) {
      exchange.getResponse().getHeaders().setCacheControl("no-store");
      return error(exchange, HttpStatus.FORBIDDEN, "COMMON_FORBIDDEN", "内部接口不允许通过网关访问");
    }
    if (path.startsWith("/api/stock/")) return unauthorized(exchange);
    if (isPublic(exchange.getRequest().getMethod(), path)) return chain.filter(exchange);
    String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (authorization == null || !authorization.startsWith("Bearer "))
      return unauthorized(exchange);
    String token = authorization.substring("Bearer ".length()).trim();
    if (token.isEmpty()) return unauthorized(exchange);
    Mono<TokenLookup> lookup = Mono.defer(() -> redis.opsForValue().get("auth:token:" + token))
        .map(value -> new TokenLookup(value, false))
        .defaultIfEmpty(new TokenLookup("", false))
        .onErrorReturn(new TokenLookup("", true));
    return lookup
        .flatMap(
            result -> {
              if (result.unavailable()) return unavailable(exchange);
              String[] parts = result.value().split(":", 2);
              if (parts.length != 2) return unauthorized(exchange);
              ServerWebExchange forwarded =
                  exchange
                      .mutate()
                      .request(
                          builder ->
                              builder.headers(
                                  headers -> {
                                    headers.remove("X-User-Id");
                                    headers.remove("X-User-Role");
                                    headers.add("X-User-Id", parts[0]);
                                    headers.add("X-User-Role", parts[1]);
                                  }))
                      .build();
              return chain.filter(forwarded);
            });
  }

  /** 检查服务前缀和至多两层额外 URL 编码，不改变实际转发路径。 */
  private boolean isTccInternalPath(String path) {
    String decodedPath = path;
    for (int depth = 0; depth <= 2; depth++) {
      if (decodedPath.contains("tcc-debits")) return true;
      if (!decodedPath.contains("%")) return false;
      try {
        decodedPath = UriUtils.decode(decodedPath, StandardCharsets.UTF_8);
      } catch (IllegalArgumentException malformedEncoding) {
        return false;
      }
    }
    return false;
  }

  /** 执行 isPublic 相关操作。 */
  private boolean isPublic(HttpMethod method, String path) {
    if (path.startsWith("/actuator/")) return true;
    if (path.startsWith("/api/auth/")) {
      return method == HttpMethod.POST
          && (path.equals("/api/auth/login") || path.equals("/api/auth/register"));
    }
    return method == HttpMethod.GET
        && (path.equals("/api/products")
            || path.startsWith("/api/products/")
            || path.equals("/api/categories")
            || path.startsWith("/api/categories/")
            || path.startsWith("/api/seckill/activities/"));
  }

  /** 执行 unauthorized 相关操作。 */
  private Mono<Void> unauthorized(ServerWebExchange exchange) {
    return error(exchange, HttpStatus.UNAUTHORIZED, "COMMON_UNAUTHORIZED", "请先登录");
  }

  /** Redis 查令牌失败时拒绝转发，故障响应禁止缓存。 */
  private Mono<Void> unavailable(ServerWebExchange exchange) {
    exchange.getResponse().getHeaders().setCacheControl("no-store");
    return error(exchange, HttpStatus.SERVICE_UNAVAILABLE, "REDIS_SERVICE_UNAVAILABLE",
        "依赖服务暂时不可用，请稍后重试");
  }

  /** 按既有 ApiResponse 字段写入固定错误，不回传底层异常细节。 */
  private Mono<Void> error(ServerWebExchange exchange, HttpStatus status, String code, String message) {
    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    String json = "{\"code\":\"" + code + "\",\"message\":\"" + message
        + "\",\"data\":null,\"requestId\":\"" + UUID.randomUUID() + "\"}";
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
  }

  private record TokenLookup(String value, boolean unavailable) {}
}
