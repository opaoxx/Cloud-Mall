package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayTccReceiptPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.PayTccReceiptVO;
import com.cloudmall.pay.service.PayTccBridgeService;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.PayTccReceiptService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Pay receipt Facade：先绑定 dispatch，再独立认领 CLAIMED。 */
@Service
@ConditionalOnProperty(name = "cloudmall.pay.tcc-receipt.enabled", havingValue = "true")
public class PayTccReceiptServiceImpl implements PayTccReceiptService {
  /** 原支付载荷/XID 的独立派发锚点。 */
  private final PayTccBridgeService bridge;

  /** 注册分支前独立提交的唯一认领。 */
  private final PayTccReceiptStore receipts;

  /** 由 Seata 代理登记实际分支的 Pay 资源。 */
  private final PayTccReceiptLocalResource resource;

  /** 创建 Pay receipt Facade。 */
  public PayTccReceiptServiceImpl(
      PayTccBridgeService bridge,
      PayTccReceiptStore receipts,
      PayTccReceiptLocalResource resource) {
    this.bridge = bridge;
    this.receipts = receipts;
    this.resource = resource;
  }

  /** 先独立认领，再仅由唯一 winner 注册 Pay 分支；不调用 User。 */
  @Override
  public PayTccReceiptVO prepare(PayTccDispatchDTO request) {
    PayTccDispatchVO dispatch = bridge.prepare(request);
    // 1. 锚点和认领必须在进入 LocalTCC 代理前提交。
    boolean winner = receipts.claim(dispatch);
    // 2. 重放只读取事实；异常不会归还唯一登记权限。
    if (winner && !resource.prepare(null, request))
      throw new BizException("PAY_TCC_RECEIPT_UNKNOWN", "Pay receipt Try 尚未确认", 503);
    // 3. 返回已提交事实，CLAIMED 或 TRYING 均不是支付成功。
    PayTccReceiptPO receipt = receipts.read(dispatch.payNo);
    if (receipt == null) throw new BizException("PAY_TCC_RECEIPT_UNKNOWN", "Pay receipt 未找到", 503);
    return PayTccReceiptVO.from(receipt);
  }

  /** 读取原 Pay receipt，不猜测未知状态。 */
  @Override
  public PayTccReceiptVO read(String payNo) {
    PayTccReceiptPO receipt = receipts.read(payNo);
    if (receipt == null)
      throw new BizException("PAY_TCC_RECEIPT_NOT_FOUND", "Pay receipt 不存在", 404);
    return PayTccReceiptVO.from(receipt);
  }
}
