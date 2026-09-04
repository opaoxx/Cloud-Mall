package com.cloudmall.gateway;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthenticationFilter implements GlobalFilter {
    private final ReactiveStringRedisTemplate redis;

    public AuthenticationFilter(ReactiveStringRedisTemplate redis) { this.redis = redis; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/api/stock/")) return unauthorized(exchange);
        if (isPublic(exchange.getRequest().getMethod(), path)) return chain.filter(exchange);
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) return unauthorized(exchange);
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty()) return unauthorized(exchange);
        return redis.opsForValue().get("auth:token:" + token)
                .switchIfEmpty(Mono.just(""))
                .flatMap(value -> {
                    String[] parts = value.split(":", 2);
                    if (parts.length != 2) return unauthorized(exchange);
                    ServerWebExchange forwarded = exchange.mutate().request(builder -> builder.headers(headers -> {
                        headers.remove("X-User-Id");
                        headers.remove("X-User-Role");
                        headers.add("X-User-Id", parts[0]);
                        headers.add("X-User-Role", parts[1]);
                    })).build();
                    return chain.filter(forwarded);
                });
    }

    private boolean isPublic(HttpMethod method, String path) {
        if (path.startsWith("/actuator/")) return true;
        if (path.startsWith("/api/auth/")) {
            return method == HttpMethod.POST && (path.equals("/api/auth/login") || path.equals("/api/auth/register"));
        }
        return method == HttpMethod.GET && (path.equals("/api/products") || path.startsWith("/api/products/")
                || path.equals("/api/categories") || path.startsWith("/api/categories/"));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
