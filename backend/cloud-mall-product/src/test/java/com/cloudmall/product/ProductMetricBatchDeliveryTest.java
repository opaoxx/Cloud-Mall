package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.domain.dto.ProductMetricBatchApplyResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricBatchEventResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.service.impl.metrics.ProductMetricBatchListener;
import com.cloudmall.product.service.impl.metrics.ProductMetricConsumer;
import com.cloudmall.product.service.impl.metrics.ProductMetricOutboxDispatcher;
import com.cloudmall.product.service.impl.metrics.ProductMetricService;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.cloudmall.product.util.ProductMetricEvents;
import com.rabbitmq.client.impl.AMQConnection;
import com.rabbitmq.client.impl.ChannelN;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.BlockingQueueConsumer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local callback contracts; mock physical channels do not prove real broker redelivery. */
class ProductMetricBatchDeliveryTest {
  @Test
  void recoveryPublishesBatchEligibilityBeforeSynchronousFirstCallback() throws Exception {
    Fixture fixture = new Fixture();
    fixture.prepareRecovery();
    fixture.success();
    AtomicReference<Generation> generation = new AtomicReference<>();
    doAnswer(
            call -> {
              Generation next = fixture.nextGeneration();
              generation.set(next);
              fixture.consume(fixture.messages(1, next), next.channel);
              assertNull(fixture.failure());
              assertEquals(0L, ReflectionTestUtils.getField(fixture.consumer, "restartAfter"));
              return null;
            })
        .when(fixture.container)
        .start();
    fixture.consumer.recoverListener();
    verify(fixture.container).start();
    verify(fixture.service).applyBatch(anyList());
    verify(generation.get().channel).basicAck(1, false);
    assertNotSame(fixture.channel, generation.get().channel);
    assertNull(fixture.failure());
  }

  @Test
  void knownPauseBeforeOwnedStopRejectsNewCallbackWithoutUnknownOrTagGuess() throws Exception {
    Fixture fixture = new Fixture();
    List<Message> invalid = fixture.messages(1);
    invalid.set(0, new Message(new byte[0], invalid.get(0).getMessageProperties()));
    fixture.consume(invalid);
    fixture.consume(fixture.messages(2));
    assertNull(fixture.failure());
    verify(fixture.service, never()).applyBatch(anyList());
    verify(fixture.service, never()).apply(any());
    verify(fixture.service).quarantine(anyString(), any(), isNull(), eq("BODY_SIZE"));
    verify(fixture.channel).basicNack(1, false, true);
    verify(fixture.channel, never()).basicNack(2, false, true);
    verify(fixture.channel, never()).basicAck(anyLong(), anyBoolean());
    verify(fixture.container, never()).stop(any(Runnable.class));
    fixture.consumer.recoverListener();
    verify(fixture.container).stop(any(Runnable.class));
  }

  @Test
  void knownCloseBeforeCallbackRejectsWithoutFalseOwnershipFailure() throws Exception {
    Fixture fixture = new Fixture();
    fixture.closePhysicalOnStop(fixture.channel);
    fixture.consumer.close();
    fixture.consume(fixture.messages(2));
    assertNull(fixture.failure());
    verifyNoInteractions(fixture.service);
    verify(fixture.channel, never()).basicAck(anyLong(), anyBoolean());
    verify(fixture.channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    assertNull(ReflectionTestUtils.getField(fixture.consumer, "closeFailure"));
  }

  @Test
  void recoveryStartFailureLocksBatchRecoveryAndStopsOnlyOwnedListener() {
    Fixture fixture = new Fixture();
    fixture.prepareRecovery();
    IllegalStateException original = new IllegalStateException("start failed");
    AtomicReference<Generation> generation = new AtomicReference<>();
    doAnswer(
            call -> {
              generation.set(fixture.nextGeneration());
              throw original;
            })
        .when(fixture.container)
        .start();
    doAnswer(
            call -> {
              if (generation.get() != null)
                when(generation.get().channel.isOpen()).thenReturn(false);
              ((Runnable) call.getArgument(0)).run();
              return null;
            })
        .when(fixture.container)
        .stop(any(Runnable.class));
    assertSame(
        original, assertThrows(IllegalStateException.class, fixture.consumer::recoverListener));
    assertSame(original, fixture.failure());
    verify(fixture.container).start();
    verify(fixture.container, times(2)).stop(any(Runnable.class));
    fixture.consumer.recoverListener();
    verify(fixture.container).start();
    verify(fixture.endpoints, atLeastOnce()).getListenerContainer("productMetrics");
  }

  @Test
  void newPauseInsideStartKeepsItsGenerationAndDeadline() {
    Fixture fixture = new Fixture();
    fixture.prepareRecovery();
    long oldGeneration = (Long) ReflectionTestUtils.getField(fixture.consumer, "pauseGeneration");
    AtomicReference<Long> newDeadline = new AtomicReference<>();
    AtomicReference<Generation> generation = new AtomicReference<>();
    doAnswer(
            call -> {
              Generation next = fixture.nextGeneration();
              generation.set(next);
              List<Message> invalid = fixture.messages(1, next);
              invalid.set(0, new Message(new byte[0], invalid.get(0).getMessageProperties()));
              fixture.consume(invalid, next.channel);
              newDeadline.set(
                  (Long) ReflectionTestUtils.getField(fixture.consumer, "restartAfter"));
              return null;
            })
        .when(fixture.container)
        .start();
    fixture.consumer.recoverListener();
    assertEquals(
        oldGeneration + 1, ReflectionTestUtils.getField(fixture.consumer, "pauseGeneration"));
    assertEquals(newDeadline.get(), ReflectionTestUtils.getField(fixture.consumer, "restartAfter"));
    assertTrue(newDeadline.get() > System.nanoTime());
    assertNull(fixture.failure());
    verify(fixture.container, times(2)).stop(any(Runnable.class));
  }

  @Test
  void newCloseDuringStartKeepsTerminalEligibilityAndDoesNotApplyCallback() throws Exception {
    Fixture fixture = new Fixture();
    fixture.prepareRecovery();
    CountDownLatch startEntered = new CountDownLatch(1);
    CountDownLatch allowReturn = new CountDownLatch(1);
    AtomicReference<Generation> generation = new AtomicReference<>();
    AtomicReference<Throwable> recoveryFailure = new AtomicReference<>();
    AtomicReference<Throwable> closingFailure = new AtomicReference<>();
    doAnswer(
            call -> {
              generation.set(fixture.nextGeneration());
              startEntered.countDown();
              assertTrue(allowReturn.await(3, TimeUnit.SECONDS));
              fixture.consume(fixture.messages(1, generation.get()), generation.get().channel);
              return null;
            })
        .when(fixture.container)
        .start();
    doAnswer(
            call -> {
              if (generation.get() != null)
                when(generation.get().channel.isOpen()).thenReturn(false);
              ((Runnable) call.getArgument(0)).run();
              return null;
            })
        .when(fixture.container)
        .stop(any(Runnable.class));
    Thread recovering =
        new Thread(
            () -> {
              try {
                fixture.consumer.recoverListener();
              } catch (Throwable failure) {
                recoveryFailure.set(failure);
              }
            });
    Thread closing =
        new Thread(
            () -> {
              try {
                fixture.consumer.close();
              } catch (Throwable failure) {
                closingFailure.set(failure);
              }
            });
    recovering.start();
    assertTrue(startEntered.await(3, TimeUnit.SECONDS));
    closing.start();
    awaitCloseRequest(fixture.consumer);
    allowReturn.countDown();
    recovering.join(3000);
    closing.join(3000);
    assertFalse(recovering.isAlive());
    assertFalse(closing.isAlive());
    assertNull(recoveryFailure.get());
    assertNull(closingFailure.get());
    assertNull(fixture.failure());
    assertEquals(true, ReflectionTestUtils.getField(fixture.consumer, "closed"));
    verify(fixture.service, never()).applyBatch(anyList());
    verify(fixture.service, never()).apply(any());
  }

  @Test
  void closeBetweenBatchAttemptsStopsRetryWithoutFalseUnknown() throws Exception {
    Fixture fixture = new Fixture();
    CountDownLatch applyEntered = new CountDownLatch(1);
    CountDownLatch allowFailure = new CountDownLatch(1);
    CountDownLatch stopIssued = new CountDownLatch(1);
    AtomicReference<Throwable> closingFailure = new AtomicReference<>();
    when(fixture.service.applyBatch(anyList()))
        .thenAnswer(
            call -> {
              applyEntered.countDown();
              assertTrue(allowFailure.await(3, TimeUnit.SECONDS));
              throw new ConcurrencyFailureException("first attempt ended");
            });
    doAnswer(
            call -> {
              when(fixture.channel.isOpen()).thenReturn(false);
              stopIssued.countDown();
              ((Runnable) call.getArgument(0)).run();
              return null;
            })
        .when(fixture.container)
        .stop(any(Runnable.class));
    Thread delivery = new Thread(() -> fixture.consume(fixture.messages(2)));
    Thread closing =
        new Thread(
            () -> {
              try {
                fixture.consumer.close();
              } catch (Throwable failure) {
                closingFailure.set(failure);
              }
            });
    delivery.start();
    assertTrue(applyEntered.await(3, TimeUnit.SECONDS));
    closing.start();
    assertTrue(stopIssued.await(3, TimeUnit.SECONDS));
    allowFailure.countDown();
    delivery.join(3000);
    closing.join(3000);
    assertFalse(delivery.isAlive());
    assertFalse(closing.isAlive());
    assertNull(fixture.failure());
    assertNull(closingFailure.get());
    verify(fixture.service).applyBatch(anyList());
    verify(fixture.channel, never()).basicAck(anyLong(), anyBoolean());
    verify(fixture.channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
  }

  private static void awaitCloseRequest(ProductMetricConsumer consumer)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    Object gate = ReflectionTestUtils.getField(consumer, "stateGate");
    while (System.nanoTime() < deadline) {
      synchronized (gate) {
        if ((Boolean) ReflectionTestUtils.getField(consumer, "closeRequested")) return;
      }
      Thread.sleep(1);
    }
    fail("close request did not linearize");
  }

  @Test
  void actualTransactionProxyCompletesBeforeAnyPhysicalAck() throws Exception {
    Fixture fixture = new Fixture();
    RecordingTransactionManager manager = new RecordingTransactionManager();
    ProxyFactory proxy = new ProxyFactory(fixture.service);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    ProductMetricService transactionalService = (ProductMetricService) proxy.getProxy();
    when(fixture.service.applyBatch(anyList()))
        .thenAnswer(
            call -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
              assertEquals(0, manager.commits);
              return success(call.getArgument(0));
            });
    doAnswer(
            call -> {
              assertEquals(1, manager.commits);
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
              return null;
            })
        .when(fixture.channel)
        .basicAck(anyLong(), eq(false));
    ProductMetricConsumer consumer =
        new ProductMetricConsumer(transactionalService, fixture.publisher, fixture.endpoints);
    consumer.bindBatchRegistry(fixture.channels);
    consumer.consumeBatch(fixture.messages(4), fixture.channel, fixture.channels);
    assertEquals(1, manager.commits);
    verify(fixture.channel, times(4)).basicAck(anyLong(), eq(false));
    assertNull(ReflectionTestUtils.getField(consumer, "batchSafetyFailure"));
  }

  @Test
  void normalFullBatchCommitsBeforeOrderedIndividualAcks() throws Exception {
    Fixture fixture = new Fixture();
    List<Message> messages = fixture.messages(4);
    when(fixture.service.applyBatch(anyList()))
        .thenAnswer(
            call -> {
              verify(fixture.channel, never()).basicAck(anyLong(), anyBoolean());
              return success(call.getArgument(0));
            });
    fixture.consume(messages);
    var order = inOrder(fixture.service, fixture.channel);
    order.verify(fixture.service).applyBatch(anyList());
    for (long tag = 1; tag <= 4; tag++) order.verify(fixture.channel).basicAck(tag, false);
    verify(fixture.service, never()).apply(any());
    assertNull(fixture.failure());
    assertEquals(0, ReflectionTestUtils.getField(fixture.consumer, "activeBatch"));
  }

  @Test
  void partialBatchesOneThroughThreeAreLegal() throws Exception {
    for (int count = 1; count < 4; count++) {
      int expectedSize = count;
      Fixture fixture = new Fixture();
      fixture.success();
      fixture.consume(fixture.messages(count));
      verify(fixture.service).applyBatch(argThat(events -> events.size() == expectedSize));
      verify(fixture.channel, times(count)).basicAck(anyLong(), eq(false));
      assertNull(fixture.failure());
    }
  }

  @Test
  void transientBatchThreeThenEachSingletonThreeIsExactlyFifteenApplications() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.service.applyBatch(anyList())).thenThrow(new ConcurrencyFailureException("batch"));
    doThrow(new ConcurrencyFailureException("single")).when(fixture.service).apply(any());
    when(fixture.publisher.transfer(anyString(), anyString(), any())).thenReturn(true);
    fixture.consume(fixture.messages(4));
    verify(fixture.service, times(3)).applyBatch(anyList());
    verify(fixture.service, times(12)).apply(any());
    verify(fixture.channel, times(4)).basicAck(anyLong(), eq(false));
    assertNull(fixture.failure());
  }

  @Test
  void permanentBatchFailureImmediatelyFallsBackWithoutBatchRetries() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.service.applyBatch(anyList()))
        .thenThrow(new ProductMetricEvents.InvalidEvent("MAPPING"));
    fixture.consume(fixture.messages(4));
    verify(fixture.service).applyBatch(anyList());
    verify(fixture.service, times(4)).apply(any());
    verify(fixture.channel, times(4)).basicAck(anyLong(), eq(false));
  }

  @Test
  void exhaustedBatchMayCommitHealthySingletonPrefix() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.service.applyBatch(anyList()))
        .thenThrow(new IllegalStateException("METRIC_CONTROL_EXHAUSTED"));
    fixture.consume(fixture.messages(2));
    verify(fixture.service).applyBatch(anyList());
    verify(fixture.service, times(2)).apply(any());
    verify(fixture.channel).basicAck(1, false);
    verify(fixture.channel).basicAck(2, false);
  }

  @Test
  void producerMarkerOnValidJsonRejectsOnlyThatPhysicalMessageViaOrderedFallback()
      throws Exception {
    Fixture fixture = new Fixture();
    List<Message> messages = fixture.messages(3);
    messages.get(1).getMessageProperties().setHeader("springBatchFormat", "lengthHeader4");
    when(fixture.publisher.transfer(anyString(), anyString(), any())).thenReturn(true);
    fixture.consume(messages);
    verify(fixture.service, never()).applyBatch(anyList());
    verify(fixture.service, times(2)).apply(any());
    verify(fixture.service)
        .quarantine(
            eq(ProductMetricConsumer.QUEUE),
            same(messages.get(1).getBody()),
            isNull(),
            eq("PRODUCER_BATCH_UNSUPPORTED"));
    verify(fixture.publisher)
        .transfer(
            "cloudmall.product.metrics.dlx", "cloudmall.product.metrics.dlq", messages.get(1));
    verify(fixture.channel, times(3)).basicAck(anyLong(), eq(false));
  }

  @Test
  void invalidBodyDirectlyFallsBackAndRetainsHealthyPrefixOrder() throws Exception {
    Fixture fixture = new Fixture();
    List<Message> messages = fixture.messages(3);
    messages.set(1, new Message(new byte[0], messages.get(1).getMessageProperties()));
    when(fixture.publisher.transfer(anyString(), anyString(), any())).thenReturn(true);
    fixture.consume(messages);
    var order = inOrder(fixture.service, fixture.channel);
    order.verify(fixture.service).apply(any());
    order.verify(fixture.channel).basicAck(1, false);
    order
        .verify(fixture.service)
        .quarantine(eq(ProductMetricConsumer.QUEUE), any(), isNull(), eq("BODY_SIZE"));
    order.verify(fixture.channel).basicAck(2, false);
    order.verify(fixture.service).apply(any());
    verify(fixture.service, never()).applyBatch(anyList());
  }

  @Test
  void batchLocalFingerprintConflictDirectlyUsesSingletonCore() {
    Fixture fixture = new Fixture();
    List<Message> messages = fixture.messages(2);
    ProductMetricEventDTO first = ProductMetricEvents.parse(messages.get(0).getBody());
    ProductMetricEventDTO conflicting = ProductMetricEvents.parse(messages.get(0).getBody());
    conflicting.occurredAt = "2026-10-24T12:00:00.000+08:00";
    first.occurredAt = "2026-10-24T11:00:00.000+08:00";
    messages.set(
        0, new Message(ProductMetricEvents.bytes(first), messages.get(0).getMessageProperties()));
    messages.set(
        1,
        new Message(
            ProductMetricEvents.bytes(conflicting), messages.get(1).getMessageProperties()));
    fixture.consume(messages);
    verify(fixture.service, never()).applyBatch(anyList());
    verify(fixture.service, times(2)).apply(any());
  }

  @Test
  void ackIOExceptionStopsSuffixWithoutThrowOrRepeatedSettlementAndLocksRecovery()
      throws Exception {
    Fixture fixture = new Fixture();
    fixture.success();
    IOException original = new IOException("ack uncertain");
    doThrow(original).when(fixture.channel).basicAck(2, false);
    assertDoesNotThrow(() -> fixture.consume(fixture.messages(4)));
    verify(fixture.channel).basicAck(1, false);
    verify(fixture.channel).basicAck(2, false);
    verify(fixture.channel, never()).basicAck(3, false);
    verify(fixture.channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    assertSame(original, fixture.failure());
    fixture.consumer.recoverListener();
    verify(fixture.container).stop(any(Runnable.class));
    verify(fixture.container, never()).start();
    fixture.consume(fixture.messages(1));
    verify(fixture.service).applyBatch(anyList());
  }

  @Test
  void pauseAfterAckedPrefixNacksOnlyUntouchedSuffixAndDoesNotWaitOnOwnStop() throws Exception {
    Fixture fixture = new Fixture();
    List<Message> messages = fixture.messages(4);
    messages.set(1, new Message(new byte[0], messages.get(1).getMessageProperties()));
    when(fixture.publisher.transfer(anyString(), anyString(), any())).thenReturn(false);
    fixture.consume(messages);
    verify(fixture.service).apply(any());
    verify(fixture.channel).basicAck(1, false);
    for (long tag = 2; tag <= 4; tag++) verify(fixture.channel).basicNack(tag, false, true);
    verify(fixture.channel, never()).basicNack(1, false, true);
    verify(fixture.container, never()).stop(any(Runnable.class));
    fixture.consumer.recoverListener();
    verify(fixture.container).stop(any(Runnable.class));
    assertNull(fixture.failure());
  }

  @Test
  void nackIOExceptionDoesNotSendAnyFurtherCommands() throws Exception {
    Fixture fixture = new Fixture();
    List<Message> messages = fixture.messages(3);
    messages.set(0, new Message(new byte[0], messages.get(0).getMessageProperties()));
    IOException original = new IOException("nack uncertain");
    doThrow(original).when(fixture.channel).basicNack(1, false, true);
    fixture.consume(messages);
    assertSame(original, fixture.failure());
    verify(fixture.channel).basicNack(1, false, true);
    verify(fixture.channel, never()).basicNack(2, false, true);
    verify(fixture.service, never()).apply(any());
  }

  @Test
  void fallbackAckRuntimeCannotBeMisclassifiedAsApplicationFailureOrTransferredAgain()
      throws Exception {
    Fixture fixture = new Fixture();
    List<Message> messages = fixture.messages(2);
    messages.set(1, new Message(new byte[0], messages.get(1).getMessageProperties()));
    ConcurrencyFailureException original = new ConcurrencyFailureException("transport runtime");
    doThrow(original).when(fixture.channel).basicAck(1, false);
    fixture.consume(messages);
    assertSame(original, fixture.failure());
    verify(fixture.service).apply(any());
    verify(fixture.channel).basicAck(1, false);
    verifyNoInteractions(fixture.publisher);
    verify(fixture.service, never()).quarantine(anyString(), any(), any(), anyString());
  }

  @Test
  void invalidResultStopsBeforeAnyTagOperation() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.service.applyBatch(anyList()))
        .thenReturn(new ProductMetricBatchApplyResultDTO(List.of(), 0));
    fixture.consume(fixture.messages(2));
    assertNotNull(fixture.failure());
    verify(fixture.channel, never()).basicAck(anyLong(), anyBoolean());
    verify(fixture.service, never()).apply(any());
  }

  @Test
  void unknownRuntimeIsNotRetriedOrConvertedToFallback() {
    Fixture fixture = new Fixture();
    IllegalStateException original = new IllegalStateException("METRIC_CONTROL_INVALID");
    when(fixture.service.applyBatch(anyList())).thenThrow(original);
    fixture.consume(fixture.messages(4));
    verify(fixture.service).applyBatch(anyList());
    verify(fixture.service, never()).apply(any());
    assertSame(original, fixture.failure());
  }

  @Test
  void fatalErrorIsRecordedAndRethrownWhileReleasingActiveLease() {
    Fixture fixture = new Fixture();
    AssertionError original = new AssertionError("fatal");
    when(fixture.service.applyBatch(anyList())).thenThrow(original);
    assertSame(
        original, assertThrows(AssertionError.class, () -> fixture.consume(fixture.messages(1))));
    assertSame(original, fixture.failure());
    assertEquals(0, ReflectionTestUtils.getField(fixture.consumer, "activeBatch"));
  }

  @Test
  void malformedEnvelopeAttributionNeverReachesApplication() {
    for (int invalid = 0; invalid < 5; invalid++) {
      Fixture fixture = new Fixture();
      List<Message> messages = fixture.messages(invalid == 0 ? 5 : 2);
      if (invalid == 1) messages.get(1).getMessageProperties().setDeliveryTag(1);
      if (invalid == 2) messages.get(1).getMessageProperties().setConsumerQueue("other.queue");
      if (invalid == 3) messages.get(1).getMessageProperties().setConsumerTag("other-consumer");
      if (invalid == 4) messages.get(0).getMessageProperties().setConsumerTag("unregistered");
      fixture.consume(messages);
      verifyNoInteractions(fixture.service);
      assertNotNull(fixture.failure());
    }
  }

  @Test
  void adapterBindsBeforeFirstDeliveryAndCannotReplaceRegistry() {
    Fixture fixture = new Fixture();
    new ProductMetricBatchListener(fixture.consumer, fixture.channels);
    assertSame(fixture.channels, ReflectionTestUtils.getField(fixture.consumer, "batchRegistry"));
    assertThrows(
        IllegalStateException.class,
        () -> fixture.consumer.bindBatchRegistry(new ProductMetricBatchChannelRegistry()));
  }

  @Test
  void zeroDeliveryStillRequiresPhysicalChannelClosureAndRejectsBindingAfterClose() {
    Fixture fixture = new Fixture();
    new ProductMetricBatchListener(fixture.consumer, fixture.channels);
    doAnswer(
            call -> {
              when(fixture.channel.isOpen()).thenReturn(false);
              ((Runnable) call.getArgument(0)).run();
              return null;
            })
        .when(fixture.container)
        .stop(any(Runnable.class));
    assertDoesNotThrow(fixture.consumer::close);
    verifyNoInteractions(fixture.service);
    verify(fixture.container).stop(any(Runnable.class));
    assertThrows(
        IllegalStateException.class, () -> fixture.consumer.bindBatchRegistry(fixture.channels));
    assertDoesNotThrow(fixture.channels::assertQuiescentAndChannelsClosed);
  }

  @Test
  void closeWaitsForActiveCallbackAndDeniesNextFallbackAttempt() throws Exception {
    Fixture fixture = new Fixture();
    List<Message> messages = fixture.messages(2);
    messages.set(1, new Message(new byte[0], messages.get(1).getMessageProperties()));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              assertTrue(release.await(3, TimeUnit.SECONDS));
              return null;
            })
        .when(fixture.service)
        .apply(any());
    AtomicReference<Throwable> closeFailure = new AtomicReference<>();
    CountDownLatch stopped = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean channelOpen =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    when(fixture.channel.isOpen()).thenAnswer(call -> channelOpen.get());
    doAnswer(
            call -> {
              channelOpen.set(false);
              ((Runnable) call.getArgument(0)).run();
              stopped.countDown();
              return null;
            })
        .when(fixture.container)
        .stop(any(Runnable.class));
    Thread delivery = new Thread(() -> fixture.consume(messages));
    Thread closing =
        new Thread(
            () -> {
              try {
                fixture.consumer.close();
              } catch (Throwable failure) {
                closeFailure.set(failure);
              }
            });
    try {
      delivery.start();
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      closing.start();
      assertTrue(stopped.await(3, TimeUnit.SECONDS));
      assertTrue(closing.isAlive());
      release.countDown();
      delivery.join(3000);
      closing.join(3000);
      assertFalse(delivery.isAlive());
      assertFalse(closing.isAlive());
      verify(fixture.service).apply(any());
      verify(fixture.service, never()).quarantine(anyString(), any(), any(), anyString());
      assertNotNull(fixture.failure());
      assertNotNull(closeFailure.get());
    } finally {
      release.countDown();
      delivery.join(3000);
      closing.join(16000);
      assertFalse(delivery.isAlive());
      assertFalse(closing.isAlive());
    }
  }

  private static ProductMetricBatchApplyResultDTO success(List<ProductMetricEventDTO> events) {
    List<ProductMetricBatchEventResultDTO> results = new ArrayList<>();
    for (int index = 0; index < events.size(); index++) {
      ProductMetricEventDTO event = events.get(index);
      results.add(
          new ProductMetricBatchEventResultDTO(
              index, event.eventId, ProductMetricEvents.fingerprint(event), "APPLIED"));
    }
    return new ProductMetricBatchApplyResultDTO(results, events.size());
  }

  /** Real Spring synchronization boundaries with no JDBC resource or external transaction. */
  private static final class RecordingTransactionManager
      extends AbstractPlatformTransactionManager {
    /** Number of actual proxy completion callbacks. */
    private int commits;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      assertEquals(
          TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
      assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, definition.getIsolationLevel());
      assertEquals(3, definition.getTimeout());
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }

  /** A fresh recovered transport generation used by deterministic lifecycle interleavings. */
  private static final class Generation {
    /** Fresh fixed physical channel, never a reopened previous physical object. */
    private final ChannelN channel;

    /** New pre-consume tag registered against the fresh BQC and physical connection. */
    private final String tag;

    private Generation(ChannelN channel, String tag) {
      this.channel = channel;
      this.tag = tag;
    }
  }

  /** Fixture uses the registry's actual lease and mocked fixed physical connection only. */
  private static final class Fixture {
    /** Mock application proxy boundary; individual tests install explicit outcomes. */
    private final ProductMetricService service = mock(ProductMetricService.class);

    /** Mock confirmed DLQ/retry transfer, with failure as the default. */
    private final ProductMetricOutboxDispatcher publisher =
        mock(ProductMetricOutboxDispatcher.class);

    /** Only the original productMetrics listener is exposed to lifecycle operations. */
    private final RabbitListenerEndpointRegistry endpoints =
        mock(RabbitListenerEndpointRegistry.class);

    /** Controlled listener lifecycle without starting real framework threads or connections. */
    private final SimpleMessageListenerContainer container =
        mock(SimpleMessageListenerContainer.class);

    /** Real bounded ownership registry exercising actual lease and settlement states. */
    private final ProductMetricBatchChannelRegistry channels =
        new ProductMetricBatchChannelRegistry();

    /** First fixed physical channel; recovery always creates a different object. */
    private final ChannelN channel = mock(ChannelN.class);

    /** Real unique application, settlement, pause, recovery and close owner. */
    private final ProductMetricConsumer consumer =
        new ProductMetricConsumer(service, publisher, endpoints);

    /** Original tag registered before any test callback is admitted. */
    private final String consumerTag;

    private Fixture() {
      AMQConnection connection = mock(AMQConnection.class);
      when(connection.isOpen()).thenReturn(true);
      when(channel.isOpen()).thenReturn(true);
      when(channel.getConnection()).thenReturn(connection);
      when(channel.getChannelNumber()).thenReturn(7);
      BlockingQueueConsumer source = mock(BlockingQueueConsumer.class);
      when(source.getChannel()).thenReturn(channel);
      consumerTag = channels.registerBeforeConsume(source, ProductMetricConsumer.QUEUE, channel);
      consumer.bindBatchRegistry(channels);
      when(endpoints.getListenerContainer("productMetrics")).thenReturn(container);
      doAnswer(
              call -> {
                ((Runnable) call.getArgument(0)).run();
                return null;
              })
          .when(container)
          .stop(any(Runnable.class));
    }

    private List<Message> messages(int count) {
      List<Message> messages = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue(ProductMetricConsumer.QUEUE);
        properties.setConsumerTag(consumerTag);
        properties.setDeliveryTag(index + 1L);
        ProductMetricEventDTO event =
            ProductMetricEvents.product(UUID.randomUUID().toString(), index + 1L, false, null);
        messages.add(new Message(ProductMetricEvents.bytes(event), properties));
      }
      return messages;
    }

    private List<Message> messages(int count, Generation generation) {
      List<Message> messages = messages(count);
      for (Message message : messages)
        message.getMessageProperties().setConsumerTag(generation.tag);
      return messages;
    }

    /** Prepare the actual known-pause/stop path, then expire only the monotonic test deadline. */
    private void prepareRecovery() {
      List<Message> invalid = messages(1);
      invalid.set(0, new Message(new byte[0], invalid.get(0).getMessageProperties()));
      consume(invalid);
      assertNull(failure());
      when(channel.isOpen()).thenReturn(false);
      consumer.recoverListener();
      ReflectionTestUtils.setField(consumer, "restartAfter", System.nanoTime() - 1);
    }

    /** A recovered consumer receives a new BQC, channel object, connection and unique tag. */
    private Generation nextGeneration() {
      ChannelN next = mock(ChannelN.class);
      AMQConnection connection = mock(AMQConnection.class);
      when(connection.isOpen()).thenReturn(true);
      when(next.isOpen()).thenReturn(true);
      when(next.getConnection()).thenReturn(connection);
      when(next.getChannelNumber()).thenReturn(7);
      BlockingQueueConsumer source = mock(BlockingQueueConsumer.class);
      when(source.getChannel()).thenReturn(next);
      return new Generation(
          next, channels.registerBeforeConsume(source, ProductMetricConsumer.QUEUE, next));
    }

    private void closePhysicalOnStop(ChannelN physical) {
      doAnswer(
              call -> {
                when(physical.isOpen()).thenReturn(false);
                ((Runnable) call.getArgument(0)).run();
                return null;
              })
          .when(container)
          .stop(any(Runnable.class));
    }

    private void success() {
      when(service.applyBatch(anyList()))
          .thenAnswer(call -> ProductMetricBatchDeliveryTest.success(call.getArgument(0)));
    }

    private void consume(List<Message> messages) {
      consumer.consumeBatch(messages, channel, channels);
    }

    private void consume(List<Message> messages, ChannelN physical) {
      consumer.consumeBatch(messages, physical, channels);
    }

    private Throwable failure() {
      return (Throwable) ReflectionTestUtils.getField(consumer, "batchSafetyFailure");
    }
  }
}
