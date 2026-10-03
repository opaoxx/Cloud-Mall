package com.cloudmall.stock.service;

import com.cloudmall.stock.domain.dto.Cm18SeataBranchDTO;
import com.cloudmall.stock.domain.vo.Cm18SeataBranchVO;

/** CM-18 Stock 演练编排服务，负责分支返回后的暂停和故障注入。 */
public interface Cm18SeataStockDemoOrchestrator {
  /** 执行 Stock 分支并在本地事务完成后按需注入受控故障。 */
  Cm18SeataBranchVO execute(Cm18SeataBranchDTO request, String xid, String failurePoint);
}
