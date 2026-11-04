package com.cloudmall.order.service.impl.seata;

import com.cloudmall.order.domain.dto.SeataStockDemoBranchDTO;
import com.cloudmall.order.domain.dto.SeataTransactionDemoDTO;
import com.cloudmall.order.domain.po.SeataOrderDemoPO;
import com.cloudmall.order.domain.vo.SeataStockDemoBranchVO;
import com.cloudmall.order.domain.vo.SeataTransactionDemoVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.SeataOrderDemoMapper;
import com.cloudmall.order.service.SeataDemoControlledRollbackException;
import com.cloudmall.order.service.SeataDemoUnknownTransactionException;
import com.cloudmall.order.service.SeataOrderDemoBranchService;
import com.cloudmall.order.service.SeataTransactionDemoOrchestrator;
import feign.FeignException;
import io.seata.core.context.RootContext;
import io.seata.spring.annotation.GlobalTransactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/** CM-18 Seata 全局事务编排服务，独立 Bean 以确保事务代理生效。 */
@Service("cm18SeataDemoService")
public class SeataTransactionDemoOrchestratorImpl implements SeataTransactionDemoOrchestrator {
  /** order 演练表 Mapper。 */
  private final SeataOrderDemoMapper demoMapper;

  /** order 本地分支 Bean。 */
  private final SeataOrderDemoBranchService orderBranchService;

  /** Stock 受控演练 Feign 客户端。 */
  private final StockClient stockClient;

  /** 创建 CM-18 全局事务编排服务。 */
  public SeataTransactionDemoOrchestratorImpl(
      SeataOrderDemoMapper demoMapper,
      SeataOrderDemoBranchService orderBranchService,
      StockClient stockClient) {
    this.demoMapper = demoMapper;
    this.orderBranchService = orderBranchService;
    this.stockClient = stockClient;
  }

  /**
   * 执行一次 order/stock 独立演练事务。
   *
   * @param request 受控演练请求
   * @return 演练结果；全局代理返回后由入口服务确认提交标记
   */
  @GlobalTransactional(name = "cm18-seata-demo", rollbackFor = Exception.class)
  @Override
  public SeataTransactionDemoVO execute(SeataTransactionDemoDTO request) {
    // 1. 只重放已明确COMMITTED的演练事实，局部可见行仍返回未知。
    SeataOrderDemoPO existing = demoMapper.selectById(request.txId);
    if (existing != null) {
      if (!"COMMITTED".equals(existing.status))
        throw new SeataDemoUnknownTransactionException("CM18 相同 txId 尚未确认全局提交", null);
      return new SeataTransactionDemoVO(request.txId, maskXid(existing.txXid), "COMMITTED");
    }

    // 2. 读取由 Seata TM 建立的当前全局 XID。
    String xid = RootContext.getXID();
    if (xid == null || xid.isBlank()) {
      throw new IllegalStateException("CM18 全局事务未建立 XID");
    }

    // 3. 通过独立本地事务 Bean 写入 order 分支事实，返回后才注入故障。
    orderBranchService.write(request.txId, xid);
    if ("ORDER_AFTER_WRITE".equals(request.failurePoint)) {
      throw new SeataDemoControlledRollbackException("CM18 order 分支写入后受控回滚");
    }

    // 4. 通过专用 Feign 传播 XID，调用 Stock 分支并核验响应证明。
    try {
      com.cloudmall.common.api.ApiResponse<SeataStockDemoBranchVO> stockResponse =
          stockClient.executeSeataDemoBranch(
              new SeataStockDemoBranchDTO(request.txId),
              xid,
              request.failurePoint,
              "cloud-mall-cm18-test");
      if (!isValidStockResponse(stockResponse, request.txId, xid)) {
        throw new SeataDemoUnknownTransactionException("CM18 Stock 分支响应不可确认", null);
      }
    } catch (FeignException exception) {
      if ("STOCK_AFTER_WRITE".equals(request.failurePoint)
          && exception.contentUTF8().contains("CM18_CONTROLLED_ROLLBACK")) {
        throw new SeataDemoControlledRollbackException("CM18 stock 分支写入后受控回滚");
      }
      throw new SeataDemoUnknownTransactionException("CM18 Stock/TC 状态未知", exception);
    }

    // 5. 保留Stock分支后的受控回滚点；成功结果交给全局代理完成决议。
    if ("STOCK_AFTER_WRITE".equals(request.failurePoint)) {
      throw new SeataDemoControlledRollbackException("CM18 stock 分支写入后受控回滚");
    }
    return new SeataTransactionDemoVO(request.txId, maskXid(xid), "COMMITTED");
  }

  /** 校验 Stock 分支已写入同一请求、同一 XID 的明确成功响应。 */
  private boolean isValidStockResponse(
      com.cloudmall.common.api.ApiResponse<SeataStockDemoBranchVO> response,
      String txId,
      String xid) {
    if (response == null || !"0".equals(response.code) || response.data == null) return false;
    SeataStockDemoBranchVO branch = response.data;
    return txId.equals(branch.txId)
        && "WRITTEN".equals(branch.status)
        && branch.xid != null
        && branch.xid.startsWith(maskXid(xid));
  }

  /** 将完整 XID 转换为不可逆的短标识。 */
  private String maskXid(String xid) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(xid.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 不可用", exception);
    }
  }
}
