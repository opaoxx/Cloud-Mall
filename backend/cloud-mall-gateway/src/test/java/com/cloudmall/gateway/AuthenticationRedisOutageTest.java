package com.cloudmall.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.cloudmall.gateway.filter.AuthenticationFilter;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class AuthenticationRedisOutageTest {
  private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
  private final ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
  private final AuthenticationFilter filter = new AuthenticationFilter(redis);
  private final AtomicInteger forwarded = new AtomicInteger();
  private final GatewayFilterChain chain = exchange -> {
    forwarded.incrementAndGet();
    return Mono.empty();
  };

  @Test
  void protectedRouteRejectsRedisFailureWith503AndNoStore() {
    assertEquals(Ordered.HIGHEST_PRECEDENCE, filter.getOrder());
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("auth:token:test-token"))
        .thenReturn(Mono.error(new RedisConnectionFailureException("redis down")));
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/cart").header("Authorization", "Bearer test-token"));

    filter.filter(exchange, chain).block();

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
    assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
    assertTrue(exchange.getResponse().getBodyAsString().block()
        .contains("\"code\":\"REDIS_SERVICE_UNAVAILABLE\""));
    assertEquals(0, forwarded.get());
  }

  @Test
  void missingTokenInAvailableRedisRemains401() {
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("auth:token:missing")).thenReturn(Mono.empty());
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/cart").header("Authorization", "Bearer missing"));

    filter.filter(exchange, chain).block();

    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    assertEquals(0, forwarded.get());
  }

  @Test
  void validTokenForwardsExactlyOnceWhenDownstreamCompletesEmpty() {
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("auth:token:valid")).thenReturn(Mono.just("33:USER"));
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/cart").header("Authorization", "Bearer valid"));

    filter.filter(exchange, chain).block();

    assertEquals(1, forwarded.get());
    assertNull(exchange.getResponse().getStatusCode());
  }

  @Test
  void anonymousProductGetNeverLooksUpRedis() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/products"));

    filter.filter(exchange, chain).block();

    assertEquals(1, forwarded.get());
    verifyNoInteractions(redis);
  }
}
