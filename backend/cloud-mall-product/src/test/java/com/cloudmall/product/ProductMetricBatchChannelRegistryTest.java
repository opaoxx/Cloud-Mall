package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.config.ProductMetricBatchContainerFactory;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry.Lease;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry.SettlementStatus;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.impl.AMQConnection;
import com.rabbitmq.client.impl.ChannelN;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ChannelProxy;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannelImpl;
import org.springframework.amqp.rabbit.listener.BlockingQueueConsumer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

class ProductMetricBatchChannelRegistryTest {
  /** Original application queue. */
  private static final String QUEUE = "cloudmall.product.metrics.queue";

  /** Real audited publisher wrapper can coexist with physical-only, single-tag dispositions. */
  @Test
  void acceptsCorrelatedSpringPublisherChainAndSendsOnlyBoundPhysical() throws Exception {
    ProductMetricBatchChannelRegistry registry = new ProductMetricBatchChannelRegistry();
    ChannelN physical = physical(4);
    PublisherCallbackChannelImpl publisher =
        new PublisherCallbackChannelImpl(physical, mock(ExecutorService.class));
    ChannelProxy outer = springProxy(publisher, false);
    String tag = registry.registerBeforeConsume(consumer(outer), QUEUE, outer);
    Lease lease = registry.acquire(tag, outer, List.of(1L, 2L, 3L));
    lease.assertCurrent();
    lease.ack(1L);
    lease.nack(2L);
    assertEquals(SettlementStatus.ACK_CALL_RETURNED, lease.status(1L));
    assertEquals(SettlementStatus.NACK_CALL_RETURNED, lease.status(2L));
    assertEquals(SettlementStatus.UNTOUCHED, lease.status(3L));
    verify(physical).basicAck(1L, false);
    verify(physical).basicNack(2L, false, true);
    assertThrows(IllegalStateException.class, () -> lease.ack(1L));
    assertThrows(IllegalStateException.class, registry::assertQuiescentAndChannelsClosed);
    lease.release();
    lease.release();
    assertThrows(IllegalStateException.class, lease::assertCurrent);
    when(physical.isOpen()).thenReturn(false);
    registry.assertQuiescentAndChannelsClosed();
    verify(physical.getConnection(), never()).close();
  }

  /** An old queued delivery cannot be admitted after its mutable proxy retargets. */
  @Test
  void targetSwapPermanentlyRetiresOldQueuedTagWithoutAnyDisposition() throws Exception {
    ProductMetricBatchChannelRegistry registry = new ProductMetricBatchChannelRegistry();
    ChannelN original = physical(7);
    ChannelN replacement = physical(7);
    ChannelProxy outer = springProxy(original, false);
    String tag = registry.registerBeforeConsume(consumer(outer), QUEUE, outer);
    Object handler = Proxy.getInvocationHandler(outer);
    ReflectionTestUtils.setField(handler, "target", replacement);
    assertThrows(IllegalStateException.class, () -> registry.acquire(tag, outer, List.of(1L)));
    ReflectionTestUtils.setField(handler, "target", original);
    assertThrows(IllegalStateException.class, () -> registry.acquire(tag, outer, List.of(1L)));
    verify(original, never()).basicAck(anyLong(), anyBoolean());
    verify(replacement, never()).basicAck(anyLong(), anyBoolean());
  }

  /**
   * SQL admission and dispositions recheck the target even after successful callback acquisition.
   */
  @Test
  void swapAfterLeaseNeverRoutesOldTagToNewPhysical() throws Exception {
    ProductMetricBatchChannelRegistry registry = new ProductMetricBatchChannelRegistry();
    ChannelN original = physical(9);
    ChannelN replacement = physical(9);
    ChannelProxy outer = springProxy(original, false);
    String tag = registry.registerBeforeConsume(consumer(outer), QUEUE, outer);
    Lease lease = registry.acquire(tag, outer, List.of(8L, 9L));
    ReflectionTestUtils.setField(Proxy.getInvocationHandler(outer), "target", replacement);
    assertThrows(IllegalStateException.class, lease::assertCurrent);
    assertThrows(IllegalStateException.class, () -> lease.ack(8L));
    assertEquals(SettlementStatus.SETTLEMENT_UNKNOWN, lease.status(8L));
    assertNotNull(lease.failure(8L));
    assertThrows(IllegalStateException.class, () -> lease.nack(8L));
    assertEquals(SettlementStatus.UNTOUCHED, lease.status(9L));
    verify(original, never()).basicAck(anyLong(), anyBoolean());
    verify(replacement, never()).basicAck(anyLong(), anyBoolean());
    lease.release();
  }

  /** IOException retains the same object, unknown tag and untouched suffix without a resend. */
  @Test
  void failedAckIsUnknownAndRetiresLease() throws Exception {
    ProductMetricBatchChannelRegistry registry = new ProductMetricBatchChannelRegistry();
    ChannelN physical = physical(2);
    String tag = registry.registerBeforeConsume(consumer(physical), QUEUE, physical);
    Lease lease = registry.acquire(tag, physical, List.of(1L, 2L));
    IOException failure = new IOException("lost-call-result");
    doThrow(failure).when(physical).basicAck(1L, false);
    assertSame(failure, assertThrows(IOException.class, () -> lease.ack(1L)));
    assertSame(failure, lease.failure(1L));
    assertEquals(SettlementStatus.SETTLEMENT_UNKNOWN, lease.status(1L));
    assertEquals(SettlementStatus.UNTOUCHED, lease.status(2L));
    assertThrows(IllegalStateException.class, () -> lease.nack(1L));
    assertThrows(IllegalStateException.class, lease::assertCurrent);
    verify(physical, times(1)).basicAck(1L, false);
    verify(physical, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    lease.release();
  }

  /** A NACK failure has the same non-resend semantics as an ACK failure. */
  @Test
  void failedNackPreservesCauseAndNeverTouchesSuffix() throws Exception {
    ProductMetricBatchChannelRegistry registry = new ProductMetricBatchChannelRegistry();
    ChannelN physical = physical(2);
    String tag = registry.registerBeforeConsume(consumer(physical), QUEUE, physical);
    Lease lease = registry.acquire(tag, physical, List.of(1L, 2L));
    IOException failure = new IOException("lost-nack-result");
    doThrow(failure).when(physical).basicNack(1L, false, true);
    assertSame(failure, assertThrows(IOException.class, () -> lease.nack(1L)));
    assertSame(failure, lease.failure(1L));
    assertThrows(IllegalStateException.class, () -> lease.nack(1L));
    assertEquals(SettlementStatus.SETTLEMENT_UNKNOWN, lease.status(1L));
    assertEquals(SettlementStatus.UNTOUCHED, lease.status(2L));
    verify(physical, times(1)).basicNack(1L, false, true);
    verify(physical, never()).basicAck(anyLong(), anyBoolean());
    lease.release();
  }

  /** A stalled client command cannot monopolize the registry monitor and extend close's budget. */
  @Test
  void pendingDispositionDoesNotBlockClosureCheckOrAllowEarlyRelease() throws Exception {
    ProductMetricBatchChannelRegistry registry = new ProductMetricBatchChannelRegistry();
    ChannelN physical = physical(2);
    String tag = registry.registerBeforeConsume(consumer(physical), QUEUE, physical);
    Lease lease = registry.acquire(tag, physical, List.of(1L));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    doAnswer(
            invocation -> {
              entered.countDown();
              if (!finish.await(2, TimeUnit.SECONDS)) throw new IOException("test release timeout");
              return null;
            })
        .when(physical)
        .basicAck(1L, false);
    Thread settlement =
        new Thread(
            () -> {
              try {
                lease.ack(1L);
              } catch (Throwable thrown) {
                failure.set(thrown);
              }
            },
            "metric-registry-test-settlement");
    settlement.start();
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertTimeout(
          Duration.ofMillis(500),
          () ->
              assertThrows(
                  IllegalStateException.class, registry::assertQuiescentAndChannelsClosed));
      assertThrows(IllegalStateException.class, lease::release);
      assertEquals(SettlementStatus.SETTLEMENT_UNKNOWN, lease.status(1L));
    } finally {
      finish.countDown();
      settlement.join(2000);
    }
    assertFalse(settlement.isAlive());
    assertNull(failure.get());
    assertEquals(SettlementStatus.ACK_CALL_RETURNED, lease.status(1L));
    lease.release();
  }

  /** Closed physical generations continue to occupy the cap while callbacks retain their leases. */
  @Test
  void capNeedsPhysicalClosureAndLeaseReleaseBeforeNewGeneration() {
    ProductMetricBatchChannelRegistry registry = new ProductMetricBatchChannelRegistry();
    ChannelN first = physical(1);
    ChannelN second = physical(1);
    ChannelN third = physical(1);
    String firstTag = registry.registerBeforeConsume(consumer(first), QUEUE, first);
    String secondTag = registry.registerBeforeConsume(consumer(second), QUEUE, second);
    Lease lease = registry.acquire(firstTag, first, List.of(1L));
    assertThrows(
        IllegalStateException.class,
        () -> registry.registerBeforeConsume(consumer(third), QUEUE, third));
    when(first.isOpen()).thenReturn(false);
    assertThrows(
        IllegalStateException.class,
        () -> registry.registerBeforeConsume(consumer(third), QUEUE, third));
    lease.release();
    String thirdTag = registry.registerBeforeConsume(consumer(third), QUEUE, third);
    assertNotEquals(firstTag, thirdTag);
    assertNotEquals(secondTag, thirdTag);
    assertThrows(IllegalStateException.class, () -> registry.acquire(firstTag, third, List.of(1L)));
    assertThrows(
        IllegalStateException.class, () -> registry.acquire(secondTag, third, List.of(1L)));
  }

  /** Tags and callback channels are not globally reusable identities. */
  @Test
  void rejectsBadQueueRawTagShapesUnknownTagsAndRepeatedConsumer() {
    ProductMetricBatchChannelRegistry registry = new ProductMetricBatchChannelRegistry();
    ChannelN physical = physical(1);
    BlockingQueueConsumer consumer = consumer(physical);
    assertThrows(
        IllegalStateException.class,
        () -> registry.registerBeforeConsume(consumer, "other.queue", physical));
    String tag = registry.registerBeforeConsume(consumer, QUEUE, physical);
    assertThrows(
        IllegalStateException.class,
        () -> registry.registerBeforeConsume(consumer, QUEUE, physical));
    for (List<Long> bad :
        List.of(
            List.<Long>of(),
            List.of(0L),
            List.of(2L, 1L),
            List.of(1L, 1L),
            List.of(1L, 2L, 3L, 4L, 5L))) {
      assertThrows(IllegalStateException.class, () -> registry.acquire(tag, physical, bad));
    }
    assertThrows(
        IllegalStateException.class,
        () -> registry.acquire("not-registered", physical, List.of(1L)));
    Lease lease = registry.acquire(tag, physical, List.of(3L, 4L, 5L, 6L));
    assertThrows(IllegalStateException.class, () -> registry.acquire(tag, physical, List.of(7L)));
    lease.release();
  }

  /**
   * Unknown proxies, nested wrappers, automatic recovery and Rabbit transactions are unsupported.
   */
  @Test
  void rejectsUnknownNestedRecoverableAndTransactionalChains() throws Exception {
    ProductMetricBatchChannelRegistry registry = new ProductMetricBatchChannelRegistry();
    ChannelN physical = physical(3);
    Channel generic = mock(Channel.class);
    Channel recovered = mock(ChannelN.class, withSettings().extraInterfaces(Recoverable.class));
    Channel unknownChannelSubclass =
        mock(ChannelN.class, withSettings().extraInterfaces(Runnable.class));
    ChannelProxy unknown = mock(ChannelProxy.class);
    when(unknown.getTargetChannel()).thenReturn(physical);
    ChannelProxy nested = springProxy(springProxy(physical, false), false);
    ChannelProxy transactional = springProxy(physical, true);
    ChannelN recoveringConnectionChannel = physical(8);
    AMQConnection recoveringConnection =
        mock(AMQConnection.class, withSettings().extraInterfaces(Recoverable.class));
    when(recoveringConnectionChannel.getConnection()).thenReturn(recoveringConnection);
    ChannelN unknownConnectionChannel = physical(9);
    AMQConnection unknownConnectionSubclass =
        mock(AMQConnection.class, withSettings().extraInterfaces(Runnable.class));
    when(unknownConnectionChannel.getConnection()).thenReturn(unknownConnectionSubclass);
    PublisherCallbackChannelImpl inner =
        new PublisherCallbackChannelImpl(physical, mock(ExecutorService.class));
    PublisherCallbackChannelImpl repeated =
        new PublisherCallbackChannelImpl(inner, mock(ExecutorService.class));
    for (Channel unsupported :
        List.of(
            generic,
            recovered,
            unknown,
            nested,
            transactional,
            repeated,
            recoveringConnectionChannel,
            unknownChannelSubclass,
            unknownConnectionChannel)) {
      assertThrows(
          IllegalStateException.class,
          () -> registry.registerBeforeConsume(consumer(unsupported), QUEUE, unsupported));
    }
  }

  /** Connection identity changes invalidate a channel even if its wrapper and number stay equal. */
  @Test
  void connectionReplacementAndBqcReplacementFailClosed() {
    ProductMetricBatchChannelRegistry registry = new ProductMetricBatchChannelRegistry();
    ChannelN physical = physical(6);
    BlockingQueueConsumer consumer = consumer(physical);
    String tag = registry.registerBeforeConsume(consumer, QUEUE, physical);
    AMQConnection replacement = mock(AMQConnection.class);
    when(replacement.isOpen()).thenReturn(true);
    when(physical.getConnection()).thenReturn(replacement);
    assertThrows(IllegalStateException.class, () -> registry.acquire(tag, physical, List.of(1L)));
    ProductMetricBatchChannelRegistry otherRegistry = new ProductMetricBatchChannelRegistry();
    ChannelN other = physical(6);
    BlockingQueueConsumer otherConsumer = consumer(other);
    String otherTag = otherRegistry.registerBeforeConsume(otherConsumer, QUEUE, other);
    when(otherConsumer.getChannel()).thenReturn(physical);
    assertThrows(
        IllegalStateException.class, () -> otherRegistry.acquire(otherTag, other, List.of(1L)));
  }

  /**
   * Invoke the real BQC consumeFromQueue and prove the tag exists before mocked physical consume.
   */
  @Test
  void controlledBqcRegistersBeforeRealFrameworkBasicConsumeAndRecoveryGetsNewTag()
      throws Exception {
    ProductMetricBatchChannelRegistry registry = new ProductMetricBatchChannelRegistry();
    ProductMetricBatchContainerFactory factory = new ProductMetricBatchContainerFactory(registry);
    SimpleMessageListenerContainer container =
        ReflectionTestUtils.invokeMethod(factory, "createContainerInstance");
    CachingConnectionFactory connectionFactory = new CachingConnectionFactory();
    connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    connectionFactory.setPublisherReturns(true);
    container.setConnectionFactory(connectionFactory);
    container.setQueueNames(QUEUE);
    container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    container.afterPropertiesSet();
    AtomicReference<String> originalTag = new AtomicReference<>();
    for (int generation = 1; generation <= 2; generation++) {
      ChannelN physical = physical(generation);
      BlockingQueueConsumer consumer =
          ReflectionTestUtils.invokeMethod(container, "createBlockingQueueConsumer");
      ReflectionTestUtils.setField(consumer, "channel", physical);
      doAnswer(
              invocation -> {
                String tag = invocation.getArgument(2);
                Lease lease = registry.acquire(tag, physical, List.of(1L));
                lease.assertCurrent();
                lease.release();
                if (originalTag.get() == null) originalTag.set(tag);
                else assertNotEquals(originalTag.get(), tag);
                return tag;
              })
          .when(physical)
          .basicConsume(
              eq(QUEUE),
              eq(false),
              anyString(),
              eq(false),
              eq(false),
              anyMap(),
              any(com.rabbitmq.client.Consumer.class));
      ReflectionTestUtils.invokeMethod(consumer, "consumeFromQueue", QUEUE);
      verify(physical)
          .basicConsume(
              eq(QUEUE),
              eq(false),
              anyString(),
              eq(false),
              eq(false),
              anyMap(),
              any(com.rabbitmq.client.Consumer.class));
      assertThrows(
          IllegalStateException.class,
          () -> ReflectionTestUtils.invokeMethod(consumer, "consumeFromQueue", QUEUE));
      verify(physical, times(1))
          .basicConsume(
              eq(QUEUE),
              eq(false),
              anyString(),
              eq(false),
              eq(false),
              anyMap(),
              any(com.rabbitmq.client.Consumer.class));
      when(physical.isOpen()).thenReturn(false);
    }
    registry.assertQuiescentAndChannelsClosed();
  }

  /** Use real audited client types as test doubles; generic Channel is deliberately rejected. */
  private ChannelN physical(int number) {
    ChannelN channel = mock(ChannelN.class);
    AMQConnection connection = mock(AMQConnection.class);
    when(connection.isOpen()).thenReturn(true);
    when(channel.getConnection()).thenReturn(connection);
    when(channel.getChannelNumber()).thenReturn(number);
    when(channel.isOpen()).thenReturn(true);
    return channel;
  }

  private BlockingQueueConsumer consumer(Channel channel) {
    BlockingQueueConsumer consumer = mock(BlockingQueueConsumer.class);
    when(consumer.getChannel()).thenReturn(channel);
    return consumer;
  }

  /** Instantiate the actual audited Spring invocation handler without any network/CF hook. */
  private ChannelProxy springProxy(Channel target, boolean transactional) throws Exception {
    CachingConnectionFactory factory = new CachingConnectionFactory();
    factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    factory.setPublisherReturns(true);
    Class<?> handlerClass =
        Class.forName(
            "org.springframework.amqp.rabbit.connection.CachingConnectionFactory$CachedChannelInvocationHandler");
    Constructor<?> constructor = handlerClass.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    InvocationHandler handler =
        (InvocationHandler)
            constructor.newInstance(
                factory,
                ReflectionTestUtils.getField(factory, "connection"),
                target,
                new LinkedList<ChannelProxy>(),
                transactional);
    return (ChannelProxy)
        Proxy.newProxyInstance(
            ChannelProxy.class.getClassLoader(), new Class<?>[] {ChannelProxy.class}, handler);
  }
}
