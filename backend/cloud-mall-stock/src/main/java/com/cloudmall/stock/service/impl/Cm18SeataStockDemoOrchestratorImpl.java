package com.cloudmall.stock.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.dto.Cm18SeataBranchDTO;
import com.cloudmall.stock.domain.vo.Cm18SeataBranchVO;
import com.cloudmall.stock.service.Cm18SeataStockDemoOrchestrator;
import com.cloudmall.stock.service.Cm18SeataStockDemoService;

/** CM-18 独立编排 Bean，确保故障注入发生在事务分支方法返回后。 */
public class Cm18SeataStockDemoOrchestratorImpl implements Cm18SeataStockDemoOrchestrator {
  /** 在独立事务中提交演练分支的服务。 */
  private final Cm18SeataStockDemoService branchService;
  /** 本地分支提交后的可控观察窗口，单位毫秒。 */
  private final int pauseAfterWriteMs;

  /** 创建 Stock 演练编排服务并限制暂停窗口。 */
  public Cm18SeataStockDemoOrchestratorImpl(
      Cm18SeataStockDemoService branchService, int pauseAfterWriteMs) {
    if (pauseAfterWriteMs < 0 || pauseAfterWriteMs > 30000) {
      throw new IllegalArgumentException("CM-18 pause must be between 0 and 30000 ms");
    }
    this.branchService = branchService;
    this.pauseAfterWriteMs = pauseAfterWriteMs;
  }

  /** 调用事务分支、暂停观察窗口并注入受控回滚。 */
  @Override
  public Cm18SeataBranchVO execute(
      Cm18SeataBranchDTO request, String xid, String failurePoint) {
    // 1. 先调用独立事务 Bean，确保本地分支事务已返回。
    Cm18SeataBranchVO result = branchService.writeBranch(request, xid);
    // 2. 保留可控窗口供进程终止测试观察。
    if (pauseAfterWriteMs > 0) {
      try {
        Thread.sleep(pauseAfterWriteMs);
      } catch (InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("CM18 pause was interrupted", interruptedException);
      }
    }
    // 3. 仅在分支事务完成后抛出受控业务异常。
    if ("STOCK_AFTER_WRITE".equals(failurePoint)) {
      throw new BizException("CM18_CONTROLLED_ROLLBACK", "CM18 STOCK_AFTER_WRITE failure", 409);
    }
    return result;
  }
}
