package com.cloudmall.pay;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.pay.domain.po.PayCallbackLogPO;
import com.cloudmall.pay.mapper.PayCallbackLogMapper;
import com.cloudmall.pay.service.impl.callback.PayCallbackReceiptRecoveryScheduler;
import com.cloudmall.pay.service.impl.callback.PayCallbackReceiptService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 有界扫描、坏前缀公平性和实例重启游标边界。 */
class PayCallbackReceiptRecoverySchedulerTest {
  /** 一整批坏载荷也须前移游标，下一批正常回调不会永久饥饿。 */
  @Test
  void badBatchAdvancesAndLaterCandidateCompletes() {
    PayCallbackLogMapper logs = mock(PayCallbackLogMapper.class);
    PayCallbackReceiptService receipts = mock(PayCallbackReceiptService.class);
    List<PayCallbackLogPO> badBatch = new ArrayList<>();
    for (int index = 1; index <= 50; index++) {
      PayCallbackLogPO row = candidate(index);
      badBatch.add(row);
      doThrow(new IllegalStateException("invalid payload"))
          .when(receipts)
          .recoverReceived(row.callbackId);
    }
    when(logs.findCompletableAfter(null, 50)).thenReturn(badBatch);
    when(logs.findCompletableAfter(50L, 50)).thenReturn(List.of(candidate(51)));
    PayCallbackReceiptRecoveryScheduler scheduler =
        new PayCallbackReceiptRecoveryScheduler(logs, receipts);

    scheduler.recoverDue();
    scheduler.recoverDue();

    verify(receipts).recoverReceived("callback-51");
    verify(logs).findCompletableAfter(null, 50);
    verify(logs).findCompletableAfter(50L, 50);
    verifyNoMoreInteractions(logs);
  }

  /** 批内一条异常不回滚或阻断后续独立收据处理。 */
  @Test
  void oneBadCandidateDoesNotBlockRestOfBatchAndWrapIsBounded() {
    PayCallbackLogMapper logs = mock(PayCallbackLogMapper.class);
    PayCallbackReceiptService receipts = mock(PayCallbackReceiptService.class);
    when(logs.findCompletableAfter(null, 50))
        .thenReturn(List.of(candidate(1), candidate(2)), List.of());
    when(logs.findCompletableAfter(2L, 50)).thenReturn(List.of());
    doThrow(new IllegalStateException("bad payload")).when(receipts).recoverReceived("callback-1");
    PayCallbackReceiptRecoveryScheduler scheduler =
        new PayCallbackReceiptRecoveryScheduler(logs, receipts);

    scheduler.recoverDue();
    scheduler.recoverDue();

    InOrder calls = inOrder(logs, receipts);
    calls.verify(logs).findCompletableAfter(null, 50);
    calls.verify(receipts).recoverReceived("callback-1");
    calls.verify(receipts).recoverReceived("callback-2");
    calls.verify(logs).findCompletableAfter(2L, 50);
    calls.verify(logs).findCompletableAfter(null, 50);
    verifyNoMoreInteractions(logs, receipts);
  }

  /** 重启实例从头发现旧received记录，不依赖前一实例内存游标。 */
  @Test
  void restartedSchedulerFindsExistingBacklogFromBeginning() {
    PayCallbackLogMapper logs = mock(PayCallbackLogMapper.class);
    PayCallbackReceiptService receipts = mock(PayCallbackReceiptService.class);
    when(logs.findCompletableAfter(isNull(), anyInt())).thenReturn(List.of(candidate(9)));
    new PayCallbackReceiptRecoveryScheduler(logs, receipts).recoverDue();
    new PayCallbackReceiptRecoveryScheduler(logs, receipts).recoverDue();
    verify(logs, times(2)).findCompletableAfter(null, 50);
    verify(receipts, times(2)).recoverReceived("callback-9");
  }

  private static PayCallbackLogPO candidate(long identity) {
    PayCallbackLogPO row = new PayCallbackLogPO();
    row.id = identity;
    row.callbackId = "callback-" + identity;
    return row;
  }
}
