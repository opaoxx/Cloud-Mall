package com.cloudmall.product;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

/** Owns the pipeline's manual administration factory and its otherwise unmanaged deferred tasks. */
final class OwnedProductMetricAdminConnection implements AutoCloseable {
  /** One shared close deadline bounds the remaining wait after synchronous factory destruction. */
  private static final long CLOSE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);

  /** The fixture's manual connection factory, including its default publisher factory. */
  private final CachingConnectionFactory connectionFactory;

  /** The one explicitly owned executor supplied to both parent and default publisher factories. */
  private final ExecutorService executor;

  /** Create the original isolated administration client without opening a broker connection. */
  OwnedProductMetricAdminConnection(String host, int port, String virtualHost) {
    this(
        new CachingConnectionFactory(host, port),
        Executors.newCachedThreadPool(
            new CustomizableThreadFactory("cm21-owned-admin-rabbit-" + UUID.randomUUID() + "-")),
        virtualHost);
  }

  /**
   * Inject only local test resources while retaining the same configuration and close algorithm.
   */
  OwnedProductMetricAdminConnection(
      CachingConnectionFactory connectionFactory, ExecutorService executor, String virtualHost) {
    this.connectionFactory = Objects.requireNonNull(connectionFactory);
    this.executor = Objects.requireNonNull(executor);
    try {
      // 1. Preserve the fixture's authentication, namespace and publisher observation settings.
      connectionFactory.setUsername("root");
      connectionFactory.setPassword("root");
      connectionFactory.setVirtualHost(virtualHost);
      connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
      connectionFactory.setPublisherReturns(true);
      // 2. Bind ownership before a channel or asynchronous close can allocate deferred tasks.
      connectionFactory.setExecutor(executor);
    } catch (RuntimeException | Error failure) {
      // 3. A failed configuration cannot leave an owned pool relying on the unfinished fixture.
      try {
        close();
      } catch (RuntimeException | Error cleanupFailure) {
        if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  /** Return the original client used by RabbitAdmin; creating it never opens a connection. */
  CachingConnectionFactory connectionFactory() {
    return connectionFactory;
  }

  /** Report shutdown initiation separately from proof that all owned tasks have terminated. */
  boolean executorShutdown() {
    return executor.isShutdown();
  }

  /** Report the actual executor termination state for the fixture's immutable close evidence. */
  boolean executorTerminated() {
    return executor.isTerminated();
  }

  /** Destroy the client and always shut down its external pool, preserving every close failure. */
  @Override
  public void close() {
    // 1. Factory destruction handles physical channels/connections before its executor is stopped.
    long deadline = System.nanoTime() + CLOSE_TIMEOUT_NANOS;
    Throwable failure = null;
    try {
      connectionFactory.destroy();
    } catch (RuntimeException | Error destroyFailure) {
      failure = destroyFailure;
    } finally {
      // 2. This pool belongs to the fixture, so even a failed factory destroy must release it.
      try {
        executor.shutdown();
      } catch (RuntimeException | Error shutdownFailure) {
        failure = retainFailure(failure, shutdownFailure);
      }
      try {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS))
          throw new IllegalStateException("CM21_ADMIN_EXECUTOR_TERMINATION_TIMEOUT");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        failure =
            retainFailure(
                failure,
                new IllegalStateException("CM21_ADMIN_EXECUTOR_CLOSE_INTERRUPTED", interrupted));
      } catch (RuntimeException | Error awaitFailure) {
        failure = retainFailure(failure, awaitFailure);
      }
    }
    // 3. Callers retain the fixture on failure; termination is never inferred from shutdown alone.
    if (failure instanceof Error) throw (Error) failure;
    if (failure != null) throw (RuntimeException) failure;
  }

  /** Keep the earliest failure as the cause while retaining independent cleanup failures. */
  private static Throwable retainFailure(Throwable original, Throwable additional) {
    if (original == null) return additional;
    if (original != additional) original.addSuppressed(additional);
    return original;
  }
}
