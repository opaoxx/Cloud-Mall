package com.cloudmall.order.service.impl.seata;

import com.cloudmall.order.domain.po.SeataOrderDemoPO;
import com.cloudmall.order.mapper.SeataOrderDemoMapper;
import com.cloudmall.order.service.SeataOrderDemoBranchService;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CM-18 order 侧本地事务分支，独立于全局事务编排 Bean。 */
@Service("cm18SeataOrderBranchService")
public class SeataOrderDemoBranchServiceImpl implements SeataOrderDemoBranchService {
  /** CM-18 order 演练表 Mapper。 */
  private final SeataOrderDemoMapper demoMapper;

  /** 创建 order 分支服务。 */
  public SeataOrderDemoBranchServiceImpl(SeataOrderDemoMapper demoMapper) {
    this.demoMapper = demoMapper;
  }

  /**
   * 在本地事务中写入 order 演练事实。
   *
   * @param txId 演练请求标识
   * @param xid Seata 全局事务 XID
   */
  @Transactional
  @Override
  public void write(String txId, String xid) {
    // 1. 记录当前全局XID对应的本地演练事实和原始创建时间。
    SeataOrderDemoPO row = new SeataOrderDemoPO();
    row.txId = txId;
    row.txXid = xid;
    row.status = "CREATED";
    row.createdAt = LocalDateTime.now();
    // 2. 只写入独立演练表，提交与回滚由调用本Bean的事务代理负责。
    demoMapper.insert(row);
  }
}
