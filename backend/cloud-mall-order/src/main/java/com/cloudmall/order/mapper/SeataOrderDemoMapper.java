package com.cloudmall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.order.domain.po.SeataOrderDemoPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Component;

/** CM-18 Seata order 演练表数据访问接口。 */
@Mapper
@Component("cm18SeataOrderDemoMapper")
public interface SeataOrderDemoMapper extends BaseMapper<SeataOrderDemoPO> {
  /** 全局提交已由 Seata TM 确认后，单独标记演练行可供幂等读取。 */
  @Update(
      "UPDATE seata_cm18_order_demo SET status='COMMITTED'"
          + " WHERE tx_id=#{txId} AND status='CREATED'")
  int markCommitted(@Param("txId") String txId);
}
