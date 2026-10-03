package com.cloudmall.order.service.impl;

import com.cloudmall.order.domain.po.Cm18SeataOrderDemoPO;
import com.cloudmall.order.mapper.Cm18SeataOrderDemoMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CM-18 order 侧本地事务分支，独立于全局事务编排 Bean。 */
@Service
public class Cm18SeataOrderBranchService {
  /** CM-18 order 演练表 Mapper。 */
  private final Cm18SeataOrderDemoMapper demoMapper;

  /** 创建 order 分支服务。 */
  public Cm18SeataOrderBranchService(Cm18SeataOrderDemoMapper demoMapper) {
    this.demoMapper = demoMapper;
  }

  /**
   * 在本地事务中写入 order 演练事实。
   *
   * @param txId 演练请求标识
   * @param xid Seata 全局事务 XID
   */
  @Transactional
  public void write(String txId, String xid) {
    Cm18SeataOrderDemoPO row = new Cm18SeataOrderDemoPO();
    row.txId = txId;
    row.txXid = xid;
    row.status = "CREATED";
    row.createdAt = LocalDateTime.now();
    demoMapper.insert(row);
  }
}
