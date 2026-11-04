package com.cloudmall.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.gateway.filter.AuthenticationFilter;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.handler.FilteringWebHandler;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 固定重构前的路径、身份头及响应式故障契约，并经过真实 Gateway 过滤链验证。 */
class GatewayAuthenticationContractTest {
  /** 令牌存储边界替身。 */
  private final ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);

  /** 实际认证过滤器。 */
  private final AuthenticationFilter filter = new AuthenticationFilter(redisTemplate);

  @Test
  void anonymousAccessKeepsExactMethodAndPathRules() {
    for (RequestCase request :
        List.of(
            new RequestCase(HttpMethod.POST, "/api/auth/login", true),
            new RequestCase(HttpMethod.POST, "/api/auth/register", true),
            new RequestCase(HttpMethod.GET, "/api/auth/login", false),
            new RequestCase(HttpMethod.POST, "/api/auth/logout", false),
            new RequestCase(HttpMethod.POST, "/api/auth/login/", false),
            new RequestCase(HttpMethod.GET, "/api/products", true),
            new RequestCase(HttpMethod.GET, "/api/products/7", true),
            new RequestCase(HttpMethod.GET, "/api/products-extra", false),
            new RequestCase(HttpMethod.POST, "/api/products", false),
            new RequestCase(HttpMethod.GET, "/api/categories", true),
            new RequestCase(HttpMethod.GET, "/api/categories/7", true),
            new RequestCase(HttpMethod.GET, "/api/seckill/activities/7", true),
            new RequestCase(HttpMethod.GET, "/api/seckill/activities", false),
            new RequestCase(HttpMethod.GET, "/api/cart", false),
            new RequestCase(HttpMethod.GET, "/actuator/health", true),
            new RequestCase(HttpMethod.POST, "/actuator/health", true),
            new RequestCase(HttpMethod.OPTIONS, "/api/products", false))) {
      MockServerWebExchange exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.method(request.method(), URI.create(request.path())));
      AtomicInteger forwarded = new AtomicInteger();
      filter
          .filter(
              exchange,
              ignored -> {
                forwarded.incrementAndGet();
                return Mono.empty();
              })
          .block();
      assertEquals(request.anonymous() ? 1 : 0, forwarded.get(), request.toString());
      assertEquals(
          request.anonymous() ? null : HttpStatus.UNAUTHORIZED,
          exchange.getResponse().getStatusCode(),
          request.toString());
    }
    verifyNoInteractions(redisTemplate);
  }

  @Test
  void internalAndStockRejectionsPrecedeTokenLookupAndKeepDecodeBound() {
    for (String path :
        List.of(
            "/api/stock/tcc-debits/try",
            "/cloud-mall-user/api/internal/tcc-debits;mode=try",
            "/cloud-mall-pay/api/internal/tcc-reconciliation",
            "/cloud-mall-user/api/internal/%74cc-debits",
            "/cloud-mall-user/api/internal/%2574cc-debits",
            "/cloud-mall-user/api/internal/%252574cc-debits")) {
      MockServerWebExchange exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.method(HttpMethod.POST, URI.create(path))
                  .header("Authorization", "Bearer valid"));
      filter
          .filter(
              exchange,
              ignored -> {
                fail("Internal request reached downstream");
                return Mono.empty();
              })
          .block();
      assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(), path);
      assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
    }
    for (String path :
        List.of(
            "/api/stock/7",
            "/api/internal/%25252574cc-debits",
            "/api/internal/%25zz",
            "/api/internal/TCC-DEBITS")) {
      MockServerWebExchange exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.method(HttpMethod.GET, URI.create(path)));
      filter
          .filter(exchange, ignored -> Mono.error(new AssertionError("Unexpected forwarding")))
          .block();
      assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode(), path);
    }
    verifyNoInteractions(redisTemplate);
  }

  @Test
  void malformedBearerHeadersNeverReadRedis() {
    for (String header :
        List.of("", "Bearer", "Bearer ", "Bearer   ", "bearer token", "Basic token")) {
      MockServerWebExchange exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.get("/api/cart").header("Authorization", header));
      filter
          .filter(exchange, ignored -> Mono.error(new AssertionError("Unexpected forwarding")))
          .block();
      assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode(), header);
    }
    verifyNoInteractions(redisTemplate);
  }

  @Test
  @SuppressWarnings("unchecked")
  void validTokenReplacesSpoofedIdentityHeadersWithoutChangingRequest() {
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(values);
    when(values.get("auth:token:valid")).thenReturn(Mono.just("33:USER"));
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/cart?selected=7")
                .header("Authorization", "Bearer   valid  ")
                .header("x-user-id", "999", "888")
                .header("X-User-Role", "ADMIN")
                .header("X-Request-Id", "original-request"));
    AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    filter
        .filter(
            exchange,
            request -> {
              forwarded.set(request);
              return Mono.empty();
            })
        .block();
    assertEquals(List.of("33"), forwarded.get().getRequest().getHeaders().get("X-User-Id"));
    assertEquals(List.of("USER"), forwarded.get().getRequest().getHeaders().get("X-User-Role"));
    assertEquals(
        "original-request", forwarded.get().getRequest().getHeaders().getFirst("X-Request-Id"));
    assertEquals(exchange.getRequest().getURI(), forwarded.get().getRequest().getURI());
    verify(values).get("auth:token:valid");
    verifyNoMoreInteractions(values);
  }

  @Test
  void synchronousRedisFailureIsDeferredAndMappedTo503() {
    when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("unavailable"));
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/cart").header("Authorization", "Bearer valid"));
    Mono<Void> response =
        filter.filter(exchange, ignored -> Mono.error(new AssertionError("Unexpected forwarding")));
    verifyNoInteractions(redisTemplate);
    response.block();
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
    assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
  }

  @Test
  @SuppressWarnings("unchecked")
  void downstreamFailurePropagatesWithoutBecomingTokenLookupFailure() {
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(values);
    when(values.get("auth:token:valid")).thenReturn(Mono.just("33:USER"));
    RedisConnectionFailureException originalFailure =
        new RedisConnectionFailureException("downstream");
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/cart").header("Authorization", "Bearer valid"));
    AtomicInteger attempts = new AtomicInteger();
    assertSame(
        originalFailure,
        assertThrows(
            RedisConnectionFailureException.class,
            () ->
                filter
                    .filter(
                        exchange,
                        ignored -> {
                          attempts.incrementAndGet();
                          return Mono.error(originalFailure);
                        })
                    .block()));
    assertEquals(1, attempts.get());
    assertNull(exchange.getResponse().getStatusCode());
    verify(values).get("auth:token:valid");
  }

  @Test
  @SuppressWarnings("unchecked")
  void filteringWebHandlerPreservesHttpEnvelopeAndAuthenticatedForwarding() {
    ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(values);
    when(values.get("auth:token:valid")).thenReturn(Mono.just("33:USER"));
    GlobalFilter downstream =
        (exchange, chain) -> {
          exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
          exchange
              .getResponse()
              .getHeaders()
              .set("X-Forwarded-User", exchange.getRequest().getHeaders().getFirst("X-User-Id"));
          return exchange.getResponse().setComplete();
        };
    FilteringWebHandler handler = new FilteringWebHandler(List.of(filter, downstream));
    Route route =
        Route.async().id("cart").uri("http://unused.invalid").predicate(ignored -> true).build();
    WebTestClient client =
        WebTestClient.bindToWebHandler(handler)
            .webFilter(
                (exchange, chain) -> {
                  exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
                  return chain.filter(exchange);
                })
            .build();
    client
        .get()
        .uri("/api/cart")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .contentType("application/json")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("COMMON_UNAUTHORIZED")
        .jsonPath("$.message")
        .isEqualTo("请先登录")
        .jsonPath("$.data")
        .isEmpty()
        .jsonPath("$.requestId")
        .value(value -> UUID.fromString((String) value));
    client
        .get()
        .uri("/api/cart")
        .header("Authorization", "Bearer valid")
        .header("X-User-Id", "999")
        .exchange()
        .expectStatus()
        .isNoContent()
        .expectHeader()
        .valueEquals("X-Forwarded-User", "33");
  }

  /** 原方法、路径及是否允许匿名转发的固定用例。 */
  private record RequestCase(HttpMethod method, String path, boolean anonymous) {}
}
