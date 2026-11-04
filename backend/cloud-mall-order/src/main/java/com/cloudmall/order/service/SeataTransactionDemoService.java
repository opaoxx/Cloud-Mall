package com.cloudmall.order.service;

import com.cloudmall.order.domain.dto.SeataTransactionDemoDTO;
import com.cloudmall.order.domain.vo.SeataTransactionDemoVO;

/** 受控演练入口：在全局事务代理完成后确认可供重放的提交事实。 */
public interface SeataTransactionDemoService {
  /**
   * 执行演练并确认提交标记；不得用外层本地事务覆盖全局代理后的确认步骤。
   *
   * @param request 已通过HTTP绑定校验的演练请求
   * @return 原三字段演练结果
   * @throws SeataDemoControlledRollbackException 明确注入的受控回滚
   * @throws SeataDemoUnknownTransactionException 全局结果或提交标记无法确认
   */
  SeataTransactionDemoVO execute(SeataTransactionDemoDTO request);
}
