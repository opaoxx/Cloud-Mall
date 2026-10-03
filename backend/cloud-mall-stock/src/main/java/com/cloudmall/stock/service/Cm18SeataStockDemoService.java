package com.cloudmall.stock.service;

import com.cloudmall.stock.domain.dto.Cm18SeataBranchDTO;
import com.cloudmall.stock.domain.vo.Cm18SeataBranchVO;

/** CM-18 Stock 演练分支服务。 */
public interface Cm18SeataStockDemoService {
  /** 在当前 Seata 分支事务中写入独立演练表。 */
  Cm18SeataBranchVO writeBranch(Cm18SeataBranchDTO request, String xid);
}
