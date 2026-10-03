package com.cloudmall.stock.service.impl;

import com.cloudmall.stock.domain.dto.Cm18SeataBranchDTO;
import com.cloudmall.stock.domain.po.Cm18SeataStockDemoPO;
import com.cloudmall.stock.domain.vo.Cm18SeataBranchVO;
import com.cloudmall.stock.mapper.Cm18SeataStockDemoMapper;
import com.cloudmall.stock.service.Cm18SeataStockDemoService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import org.springframework.transaction.annotation.Transactional;

/** CM-18 Stock 独立本地事务 Bean，避免同类调用绕过 Spring 代理。 */
public class Cm18SeataStockDemoServiceImpl implements Cm18SeataStockDemoService {
  /** 仅访问独立演练表的 Mapper。 */
  private final Cm18SeataStockDemoMapper demoMapper;

  /** 创建 Stock 独立本地事务分支。 */
  public Cm18SeataStockDemoServiceImpl(Cm18SeataStockDemoMapper demoMapper) {
    this.demoMapper = demoMapper;
  }

  /** 只写入 CM-18 演练表，不接触真实库存、缓存、消息或搜索资源。 */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public Cm18SeataBranchVO writeBranch(Cm18SeataBranchDTO request, String xid) {
    // 1. 通过 Mapper 写入当前 Stock 分支的独立演练事实。
    Cm18SeataStockDemoPO demoRow = new Cm18SeataStockDemoPO();
    demoRow.setTxId(request.getTxId());
    demoRow.setTxXid(xid);
    demoRow.setMarker("CREATED");
    demoRow.setCreatedAt(LocalDateTime.now());
    demoMapper.insert(demoRow);
    // 2. 返回分支证据，XID 只返回不可逆摘要。
    return new Cm18SeataBranchVO(request.getTxId(), hashXid(xid), "WRITTEN");
  }

  /** 生成用于日志和响应关联的 XID 摘要。 */
  private String hashXid(String xid) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(xid.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 12);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
