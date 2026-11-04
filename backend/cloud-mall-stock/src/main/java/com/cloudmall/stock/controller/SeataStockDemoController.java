package com.cloudmall.stock.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.stock.domain.dto.SeataStockBranchDTO;
import com.cloudmall.stock.domain.vo.SeataStockBranchVO;
import com.cloudmall.stock.service.SeataStockDemoOrchestrator;
import io.seata.core.context.RootContext;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** CM-18 受控 Stock 分支端点；不参与普通库存业务。 */
@RestController("cm18SeataStockDemoController")
@Profile("!prod")
@ConditionalOnProperty(name = "cloudmall.seata.cm18.enabled", havingValue = "true")
@RequestMapping("/api/internal/cm18/seata/demo")
public class SeataStockDemoController {
  /** 演练专用的内部调用边界标记。 */
  private static final String INTERNAL_SERVICE = "cloud-mall-cm18-test";

  /** 禁止真实订单号进入演练表的事务 ID 格式。 */
  private static final Pattern TX_ID_PATTERN = Pattern.compile("cm18-[a-z0-9-]{1,50}");

  /** Stock 分支支持的两个受控故障点。 */
  private static final Set<String> FAILURE_POINTS = Set.of("NONE", "STOCK_AFTER_WRITE");

  /** 事务外编排和受控故障注入服务。 */
  private final SeataStockDemoOrchestrator demoOrchestrator;

  /** 创建 CM18 Stock 内部演练入口。 */
  public SeataStockDemoController(SeataStockDemoOrchestrator demoOrchestrator) {
    this.demoOrchestrator = demoOrchestrator;
  }

  /** 校验受控头并写入 Stock 演练表后按需注入故障。 */
  @PostMapping("/branch")
  @ResponseStatus(HttpStatus.OK)
  public ApiResponse<SeataStockBranchVO> writeBranch(
      @RequestHeader(value = "X-Internal-Service", required = false) String internalService,
      @RequestHeader(value = "X-CM18-Failure-Point", required = false) String failurePoint,
      @RequestHeader(value = "TX_XID", required = false) String headerXid,
      @RequestBody SeataStockBranchDTO request) {
    // 1. 端点级别再次校验，避免绕过 Filter 时信任外部请求头。
    if (!INTERNAL_SERVICE.equals(internalService)
        || !FAILURE_POINTS.contains(failurePoint)
        || request == null
        || request.getTxId() == null
        || !TX_ID_PATTERN.matcher(request.getTxId()).matches()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CM18 demo request is invalid");
    }
    String boundXid = RootContext.getXID();
    if (boundXid == null || !boundXid.equals(headerXid)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seata XID binding is invalid");
    }
    // 2. Controller 仅完成绑定校验并委托给演练编排服务。
    return ApiResponse.ok(demoOrchestrator.execute(request, boundXid, failurePoint));
  }
}
