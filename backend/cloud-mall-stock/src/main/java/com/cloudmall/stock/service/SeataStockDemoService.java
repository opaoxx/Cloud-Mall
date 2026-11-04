package com.cloudmall.stock.service;

import com.cloudmall.stock.domain.dto.SeataStockBranchDTO;
import com.cloudmall.stock.domain.vo.SeataStockBranchVO;

/** CM-18 Stock 演练分支服务。 */
public interface SeataStockDemoService {
  /** 在当前 Seata 分支事务中写入独立演练表。 */
  SeataStockBranchVO writeBranch(SeataStockBranchDTO request, String xid);
}
