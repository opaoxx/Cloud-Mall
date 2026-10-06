package com.cloudmall.pay.service.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.cloudmall.pay.domain.po.PayTccDeliveryPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.service.PayTccDeliveryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** TCC 交付调度两阶段隔离、轮换、异常和重入保护测试。 */
class PayTccDeliverySchedulerTest {
  @Test
  void missingScanFailureDoesNotSkipDueDelivery() {
    PayTccDeliveryMapper mapper = mock(PayTccDeliveryMapper.class);
    PayTccDeliveryStore store = mock(PayTccDeliveryStore.class);
    PayTccDeliveryService service = mock(PayTccDeliveryService.class);
    PayTccDeliveryPO due = delivery("due-after-missing-failure");
    when(mapper.findMissing(20)).thenThrow(new IllegalStateException("missing scan unavailable"));
    when(mapper.findDue(any(LocalDateTime.class), eq(20))).thenReturn(List.of(due));
    PayTccDeliveryScheduler scheduler = new PayTccDeliveryScheduler(mapper, store, service);

    scheduler.recoverDue();

    verify(service).deliver(due.payNo);
  }

  @Test
  void recoveryAlternatesSeedAndDuePriorityAcrossRounds() {
    PayTccDeliveryMapper mapper = mock(PayTccDeliveryMapper.class);
    PayTccDeliveryStore store = mock(PayTccDeliveryStore.class);
    PayTccDeliveryService service = mock(PayTccDeliveryService.class);
    when(mapper.findMissing(20)).thenReturn(List.of(execution("seed")));
    when(mapper.findDue(any(LocalDateTime.class), eq(20)))
        .thenReturn(List.of(delivery("due-first")), List.of(delivery("due-second")));
    PayTccDeliveryScheduler scheduler = new PayTccDeliveryScheduler(mapper, store, service);

    scheduler.recoverDue();
    scheduler.recoverDue();

    InOrder order = inOrder(mapper);
    order.verify(mapper).findMissing(20);
    order.verify(mapper, times(2)).findDue(any(LocalDateTime.class), eq(20));
    order.verify(mapper).findMissing(20);
  }

  @Test
  void dueScanFailureDoesNotPreventSeedStage() {
    PayTccDeliveryMapper mapper = mock(PayTccDeliveryMapper.class);
    PayTccDeliveryStore store = mock(PayTccDeliveryStore.class);
    PayTccDeliveryService service = mock(PayTccDeliveryService.class);
    PayTccExecutionPO seed = execution("seed-before-due-failure");
    when(mapper.findMissing(20)).thenReturn(List.of(seed));
    when(mapper.findDue(any(LocalDateTime.class), eq(20)))
        .thenThrow(new IllegalStateException("due scan unavailable"));
    PayTccDeliveryScheduler scheduler = new PayTccDeliveryScheduler(mapper, store, service);

    scheduler.recoverDue();
    scheduler.recoverDue();

    verify(store, times(2)).ensure(seed.payNo);
  }

  @Test
  void oneEnsureOrDeliveryFailureDoesNotBlockLaterItems() {
    PayTccDeliveryMapper mapper = mock(PayTccDeliveryMapper.class);
    PayTccDeliveryStore store = mock(PayTccDeliveryStore.class);
    PayTccDeliveryService service = mock(PayTccDeliveryService.class);
    PayTccExecutionPO firstSeed = execution("first-seed");
    PayTccExecutionPO secondSeed = execution("second-seed");
    PayTccDeliveryPO firstDue = delivery("first-due");
    PayTccDeliveryPO secondDue = delivery("second-due");
    when(mapper.findMissing(20)).thenReturn(List.of(firstSeed, secondSeed));
    when(mapper.findDue(any(LocalDateTime.class), eq(20))).thenReturn(List.of(firstDue, secondDue));
    doThrow(new IllegalStateException("ensure failed")).when(store).ensure(firstSeed.payNo);
    doThrow(new IllegalStateException("deliver failed")).when(service).deliver(firstDue.payNo);
    PayTccDeliveryScheduler scheduler = new PayTccDeliveryScheduler(mapper, store, service);

    scheduler.recoverDue();

    verify(store).ensure(secondSeed.payNo);
    verify(service).deliver(secondDue.payNo);
  }

  @Test
  void reentrantRecoveryIsSkippedAndFinallyReleasesRunningGuard() throws Exception {
    PayTccDeliveryMapper mapper = mock(PayTccDeliveryMapper.class);
    PayTccDeliveryStore store = mock(PayTccDeliveryStore.class);
    PayTccDeliveryService service = mock(PayTccDeliveryService.class);
    PayTccDeliveryPO firstDue = delivery("blocked-due");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(mapper.findMissing(20)).thenReturn(List.of());
    when(mapper.findDue(any(LocalDateTime.class), eq(20))).thenReturn(List.of(firstDue));
    doAnswer(
            invocation -> {
              entered.countDown();
              assertTrue(release.await(10, TimeUnit.SECONDS));
              return null;
            })
        .when(service)
        .deliver(firstDue.payNo);
    PayTccDeliveryScheduler scheduler = new PayTccDeliveryScheduler(mapper, store, service);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> active = executor.submit(scheduler::recoverDue);
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      scheduler.recoverDue();
      release.countDown();
      active.get(10, TimeUnit.SECONDS);
      scheduler.recoverDue();
      verify(service, times(2)).deliver(firstDue.payNo);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void exhaustedSeedBudgetStillRunsDueFirstOnNextRound() {
    PayTccDeliveryMapper mapper = mock(PayTccDeliveryMapper.class);
    PayTccDeliveryStore store = mock(PayTccDeliveryStore.class);
    PayTccDeliveryService service = mock(PayTccDeliveryService.class);
    ManualClock clock = new ManualClock();
    PayTccDeliveryPO due = delivery("due-after-seed-budget");
    when(mapper.findMissing(20))
        .thenAnswer(
            invocation -> {
              clock.set(clock.getAsLong() + PayTccDeliveryScheduler.BUDGET_NANOS);
              return List.of(execution("seed-budget"));
            });
    when(mapper.findDue(any(LocalDateTime.class), eq(20))).thenReturn(List.of(due));
    PayTccDeliveryScheduler scheduler = new PayTccDeliveryScheduler(mapper, store, service, clock);

    scheduler.recoverDue();
    clock.set(PayTccDeliveryScheduler.BUDGET_NANOS * 2);
    scheduler.recoverDue();

    verify(service).deliver(due.payNo);
    verify(store, never()).ensure(any());
  }

  @Test
  void exhaustedDueBudgetStillRunsSeedFirstOnNextRound() {
    PayTccDeliveryMapper mapper = mock(PayTccDeliveryMapper.class);
    PayTccDeliveryStore store = mock(PayTccDeliveryStore.class);
    PayTccDeliveryService service = mock(PayTccDeliveryService.class);
    ManualClock clock = new ManualClock();
    PayTccExecutionPO seed = execution("seed-after-due-budget");
    when(mapper.findMissing(20)).thenReturn(List.of(), List.of(seed));
    when(mapper.findDue(any(LocalDateTime.class), eq(20)))
        .thenReturn(List.of())
        .thenAnswer(
            invocation -> {
              clock.set(clock.getAsLong() + PayTccDeliveryScheduler.BUDGET_NANOS);
              return List.of(delivery("due-budget"));
            })
        .thenReturn(List.of());
    PayTccDeliveryScheduler scheduler = new PayTccDeliveryScheduler(mapper, store, service, clock);

    scheduler.recoverDue();
    scheduler.recoverDue();
    clock.set(PayTccDeliveryScheduler.BUDGET_NANOS * 3);
    scheduler.recoverDue();

    verify(store).ensure(seed.payNo);
  }

  @Test
  void exhaustedQueryBudgetStartsNoTaskOrOtherPhase() {
    PayTccDeliveryMapper mapper = mock(PayTccDeliveryMapper.class);
    PayTccDeliveryStore store = mock(PayTccDeliveryStore.class);
    PayTccDeliveryService service = mock(PayTccDeliveryService.class);
    ManualClock clock = new ManualClock();
    when(mapper.findMissing(20))
        .thenAnswer(
            invocation -> {
              clock.set(PayTccDeliveryScheduler.BUDGET_NANOS);
              return List.of(execution("query-budget"));
            });
    PayTccDeliveryScheduler scheduler = new PayTccDeliveryScheduler(mapper, store, service, clock);

    scheduler.recoverDue();

    verify(store, never()).ensure(any());
    verify(mapper, never()).findDue(any(LocalDateTime.class), anyInt());
  }

  @Test
  void exactBudgetRejectsNextItemButOneNanosecondBeforeAllowsIt() {
    PayTccDeliveryMapper mapper = mock(PayTccDeliveryMapper.class);
    PayTccDeliveryStore store = mock(PayTccDeliveryStore.class);
    PayTccDeliveryService service = mock(PayTccDeliveryService.class);
    ManualClock clock = new ManualClock();
    PayTccExecutionPO first = execution("budget-first");
    PayTccExecutionPO second = execution("budget-second");
    when(mapper.findMissing(20)).thenReturn(List.of(first, second));
    doAnswer(
            invocation -> {
              clock.set(PayTccDeliveryScheduler.BUDGET_NANOS);
              return null;
            })
        .when(store)
        .ensure(first.payNo);
    PayTccDeliveryScheduler scheduler = new PayTccDeliveryScheduler(mapper, store, service, clock);
    scheduler.recoverDue();
    verify(store).ensure(first.payNo);
    verify(store, never()).ensure(second.payNo);

    reset(mapper, store, service);
    clock.set(0);
    when(mapper.findMissing(20)).thenReturn(List.of(first, second));
    doAnswer(
            invocation -> {
              clock.set(PayTccDeliveryScheduler.BUDGET_NANOS - 1);
              return null;
            })
        .when(store)
        .ensure(first.payNo);
    scheduler.recoverDue();
    verify(store).ensure(second.payNo);
  }

  @Test
  void eachPhaseProcessesAtMostTwentyCandidates() {
    PayTccDeliveryMapper mapper = mock(PayTccDeliveryMapper.class);
    PayTccDeliveryStore store = mock(PayTccDeliveryStore.class);
    PayTccDeliveryService service = mock(PayTccDeliveryService.class);
    when(mapper.findMissing(20))
        .thenReturn(
            java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> execution("seed-" + index))
                .toList());
    when(mapper.findDue(any(LocalDateTime.class), eq(20)))
        .thenReturn(
            java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> delivery("due-" + index))
                .toList());
    PayTccDeliveryScheduler scheduler = new PayTccDeliveryScheduler(mapper, store, service);

    scheduler.recoverDue();

    verify(store, times(20)).ensure(any());
    verify(store, never()).ensure("seed-20");
    verify(service, times(20)).deliver(any());
    verify(service, never()).deliver("due-20");
  }

  private static final class ManualClock implements LongSupplier {
    /** 测试控制的单调纳秒读数。 */
    private long current;

    /** 返回当前预算时钟值。 */
    @Override
    public long getAsLong() {
      return current;
    }

    void set(long value) {
      current = value;
    }
  }

  private PayTccExecutionPO execution(String payNo) {
    PayTccExecutionPO execution = new PayTccExecutionPO();
    execution.payNo = payNo;
    return execution;
  }

  private PayTccDeliveryPO delivery(String payNo) {
    PayTccDeliveryPO delivery = new PayTccDeliveryPO();
    delivery.payNo = payNo;
    return delivery;
  }
}
