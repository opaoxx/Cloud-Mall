package com.cloudmall.order.service.impl.seata;

import com.cloudmall.order.domain.dto.SeataTransactionDemoDTO;
import com.cloudmall.order.domain.po.SeataOrderDemoPO;
import com.cloudmall.order.domain.vo.SeataTransactionDemoVO;
import com.cloudmall.order.mapper.SeataOrderDemoMapper;
import com.cloudmall.order.service.SeataDemoUnknownTransactionException;
import com.cloudmall.order.service.SeataTransactionDemoOrchestrator;
import com.cloudmall.order.service.SeataTransactionDemoService;
import org.springframework.stereotype.Service;

/** 在全局事务代理完成之后才确认已提交演练行可供幂等重放。 */
@Service("cm18SeataDemoFacade")
public class SeataTransactionDemoServiceImpl implements SeataTransactionDemoService {
  /** Seata 全局事务代理 Bean。 */
  private final SeataTransactionDemoOrchestrator globalDemoService;

  /** 只访问 Order 隔离演练表的 Mapper。 */
  private final SeataOrderDemoMapper demoMapper;

  /** 创建事务外确认服务。 */
  public SeataTransactionDemoServiceImpl(
      SeataTransactionDemoOrchestrator globalDemoService, SeataOrderDemoMapper demoMapper) {
    this.globalDemoService = globalDemoService;
    this.demoMapper = demoMapper;
  }

  /**
   * 全局事务代理返回后，才把本地可见的 CREATED 行确认为 COMMITTED。
   *
   * <p>若进程在 AT 提交后、确认标记前中断，重放保持 UNKNOWN，避免把尚未决议的分支误报已提交。
   */
  @Override
  public SeataTransactionDemoVO execute(SeataTransactionDemoDTO request) {
    // 1. 经独立代理完成 Seata 全局事务。
    SeataTransactionDemoVO result = globalDemoService.execute(request);
    // 2. 仅在全局代理明确返回成功后更新演练行确认标记。
    if (demoMapper.markCommitted(request.txId) != 1) {
      SeataOrderDemoPO existing = demoMapper.selectById(request.txId);
      if (existing == null || !"COMMITTED".equals(existing.status))
        throw new SeataDemoUnknownTransactionException("CM18 全局已返回但确认标记未知", null);
    }
    return result;
  }
}
