package com.cloudmall.order.service.impl;

import com.cloudmall.order.domain.dto.Cm18SeataBranchDTO;
import com.cloudmall.order.domain.dto.Cm18SeataDemoDTO;
import com.cloudmall.order.domain.po.Cm18SeataOrderDemoPO;
import com.cloudmall.order.domain.vo.Cm18SeataBranchVO;
import com.cloudmall.order.domain.vo.Cm18SeataDemoVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.Cm18SeataOrderDemoMapper;
import feign.FeignException;
import io.seata.core.context.RootContext;
import io.seata.spring.annotation.GlobalTransactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/** CM-18 Seata 全局事务编排服务，独立 Bean 以确保事务代理生效。 */
@Service
public class Cm18SeataDemoService {
  /** order 演练表 Mapper。 */
  private final Cm18SeataOrderDemoMapper demoMapper;

  /** order 本地分支 Bean。 */
  private final Cm18SeataOrderBranchService orderBranchService;

  /** Stock 受控演练 Feign 客户端。 */
  private final StockClient stockClient;

  /** 创建 CM-18 全局事务编排服务。 */
  public Cm18SeataDemoService(
      Cm18SeataOrderDemoMapper demoMapper,
      Cm18SeataOrderBranchService orderBranchService,
      StockClient stockClient) {
    this.demoMapper = demoMapper;
    this.orderBranchService = orderBranchService;
    this.stockClient = stockClient;
  }

  /**
   * 执行一次 order/stock 独立演练事务。
   *
   * @param request 受控演练请求
   * @return 已提交结果；受控异常由 Controller 在代理外转换为回滚结果
   */
  @GlobalTransactional(name = "cm18-seata-demo", rollbackFor = Exception.class)
  public Cm18SeataDemoVO execute(Cm18SeataDemoDTO request) {
    Cm18SeataOrderDemoPO existing = demoMapper.selectById(request.txId);
    if (existing != null) {
      if (!"COMMITTED".equals(existing.status))
        throw new Cm18UnknownTransactionException("CM18 相同 txId 尚未确认全局提交", null);
      return new Cm18SeataDemoVO(request.txId, maskXid(existing.txXid), "COMMITTED");
    }

    // 1. 读取由 Seata TM 建立的当前全局 XID。
    String xid = RootContext.getXID();
    if (xid == null || xid.isBlank()) {
      throw new IllegalStateException("CM18 全局事务未建立 XID");
    }

    // 2. 通过独立本地事务 Bean 写入 order 分支事实。
    orderBranchService.write(request.txId, xid);
    if ("ORDER_AFTER_WRITE".equals(request.failurePoint)) {
      throw new Cm18ControlledRollbackException("CM18 order 分支写入后受控回滚");
    }

    // 3. 通过专用 Feign 传播 XID，调用 Stock 分支。
    try {
      com.cloudmall.common.api.ApiResponse<Cm18SeataBranchVO> stockResponse =
          stockClient.cm18SeataBranch(
          new Cm18SeataBranchDTO(request.txId),
          xid,
          request.failurePoint,
          "cloud-mall-cm18-test");
      if (!isValidStockResponse(stockResponse, request.txId, xid)) {
        throw new Cm18UnknownTransactionException("CM18 Stock 分支响应不可确认", null);
      }
    } catch (FeignException exception) {
      if ("STOCK_AFTER_WRITE".equals(request.failurePoint)
          && exception.contentUTF8().contains("CM18_CONTROLLED_ROLLBACK")) {
        throw new Cm18ControlledRollbackException("CM18 stock 分支写入后受控回滚");
      }
      throw new Cm18UnknownTransactionException("CM18 Stock/TC 状态未知", exception);
    }

    if ("STOCK_AFTER_WRITE".equals(request.failurePoint)) {
      throw new Cm18ControlledRollbackException("CM18 stock 分支写入后受控回滚");
    }
    return new Cm18SeataDemoVO(request.txId, maskXid(xid), "COMMITTED");
  }

  /** 校验 Stock 分支已写入同一请求、同一 XID 的明确成功响应。 */
  private boolean isValidStockResponse(
      com.cloudmall.common.api.ApiResponse<Cm18SeataBranchVO> response,
      String txId,
      String xid) {
    if (response == null || !"0".equals(response.code) || response.data == null) return false;
    Cm18SeataBranchVO branch = response.data;
    return txId.equals(branch.txId)
        && "WRITTEN".equals(branch.status)
        && branch.xid != null
        && branch.xid.startsWith(maskXid(xid));
  }

  /** 将完整 XID 转换为不可逆的短标识。 */
  private String maskXid(String xid) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(xid.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 不可用", exception);
    }
  }
}
