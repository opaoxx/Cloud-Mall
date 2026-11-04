package com.cloudmall.stock.service;

import com.cloudmall.stock.domain.dto.SeataStockBranchDTO;
import com.cloudmall.stock.domain.vo.SeataStockBranchVO;

/** CM-18 Stock 演练编排服务，负责分支返回后的暂停和故障注入。 */
public interface SeataStockDemoOrchestrator {
  /** 执行 Stock 分支并在本地事务完成后按需注入受控故障。 */
  SeataStockBranchVO execute(SeataStockBranchDTO request, String xid, String failurePoint);
}
