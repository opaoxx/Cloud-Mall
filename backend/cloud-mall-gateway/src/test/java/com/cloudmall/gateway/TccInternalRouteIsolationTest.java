package com.cloudmall.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.gateway.filter.AuthenticationFilter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** 发现路由的服务名前缀不能把内部 TCC 路径重新暴露给用户。 */
class TccInternalRouteIsolationTest {
  @Test
  void reconciliationPathsCannotBeExposedThroughDiscoveryOrEncoding() {
    for (String path :
        List.of(
            "/api/internal/payments/pay1/tcc-reconciliation/order-evidence",
            "/cloud-mall-pay/api/internal/payments/pay1/%74cc-reconciliation/order-evidence",
            "/cloud-mall-pay/api/internal/payments/pay1/tcc-reconciliation;mode=inspect/order-evidence",
            "/api/internal/payments/pay1/tcc-reconciliation/user-evidence",
            "/cloud-mall-pay/api/internal/payments/pay1/%74cc-reconciliation/user-evidence",
            "/cloud-mall-pay/api/internal/payments/pay1/tcc-reconciliation;mode=inspect/user-evidence",
            "/api/internal/payments/pay1/tcc-reconciliation",
            "/cloud-mall-pay/api/internal/payments/pay1/tcc-reconciliation",
            "/cloud-mall-pay/api/internal/payments/pay1/%74cc-reconciliation",
            "/cloud-mall-pay/api/internal/payments/pay1/tcc-reconciliation;mode=inspect")) {
      ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
      AuthenticationFilter filter = new AuthenticationFilter(redis);
      AtomicBoolean forwarded = new AtomicBoolean();
      MockServerWebExchange exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.get(path)
                  .header("Authorization", "Bearer user-token")
                  .header("X-Internal-Service", "cm18-reconciliation")
                  .header("X-Internal-Callback-Token", "cloudmall-local-callback"));
      filter
          .filter(
              exchange,
              ignored -> {
                forwarded.set(true);
                return Mono.empty();
              })
          .block();
      assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
      assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
      assertFalse(forwarded.get());
      verifyNoInteractions(redis);
    }
  }

  @Test
  void directDiscoveryAndEncodedPathsAreRejectedBeforeTokenLookup() {
    for (String path :
        List.of(
            "/api/internal/users/7/balance/tcc-debits/try",
            "/cloud-mall-user/api/internal/users/7/balance/tcc-debits/tcc-debit:pay",
            "/cloud-mall-user/api/internal/users/7/balance/%74cc-debits/try",
            "/cloud-mall-user/api/internal/users/7/balance/tcc-debits;mode=try/try")) {
      ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
      AuthenticationFilter filter = new AuthenticationFilter(redis);
      AtomicBoolean forwarded = new AtomicBoolean();
      MockServerWebExchange exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.post(path)
                  .header("Authorization", "Bearer user-token")
                  .header("X-Internal-Service", "cloud-mall-pay")
                  .header("X-Internal-Callback-Token", "cloudmall-local-callback"));
      filter
          .filter(
              exchange,
              ignored -> {
                forwarded.set(true);
                return Mono.empty();
              })
          .block();
      assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(), path);
      assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
      assertFalse(forwarded.get());
      verifyNoInteractions(redis);
    }
  }
}
