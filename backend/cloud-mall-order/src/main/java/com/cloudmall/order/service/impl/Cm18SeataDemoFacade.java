package com.cloudmall.order.service.impl;

import com.cloudmall.order.domain.dto.Cm18SeataDemoDTO;
import com.cloudmall.order.domain.po.Cm18SeataOrderDemoPO;
import com.cloudmall.order.domain.vo.Cm18SeataDemoVO;
import com.cloudmall.order.mapper.Cm18SeataOrderDemoMapper;
import org.springframework.stereotype.Service;

/** 在全局事务代理完成之后才确认已提交演练行可供幂等重放。 */
@Service
public class Cm18SeataDemoFacade {
  /** Seata 全局事务代理 Bean。 */
  private final Cm18SeataDemoService globalDemoService;

  /** 只访问 Order 隔离演练表的 Mapper。 */
  private final Cm18SeataOrderDemoMapper demoMapper;

  /** 创建事务外确认服务。 */
  public Cm18SeataDemoFacade(
      Cm18SeataDemoService globalDemoService, Cm18SeataOrderDemoMapper demoMapper) {
    this.globalDemoService = globalDemoService;
    this.demoMapper = demoMapper;
  }

  /**
   * 全局事务代理返回后，才把本地可见的 CREATED 行确认为 COMMITTED。
   *
   * <p>若进程在 AT 提交后、确认标记前中断，重放保持 UNKNOWN，避免把尚未决议的分支误报已提交。
   */
  public Cm18SeataDemoVO execute(Cm18SeataDemoDTO request) {
    // 1. 经独立代理完成 Seata 全局事务。
    Cm18SeataDemoVO result = globalDemoService.execute(request);
    // 2. 仅在全局代理明确返回成功后更新演练行确认标记。
    if (demoMapper.markCommitted(request.txId) != 1) {
      Cm18SeataOrderDemoPO existing = demoMapper.selectById(request.txId);
      if (existing == null || !"COMMITTED".equals(existing.status))
        throw new Cm18UnknownTransactionException("CM18 全局已返回但确认标记未知", null);
    }
    return result;
  }
}
