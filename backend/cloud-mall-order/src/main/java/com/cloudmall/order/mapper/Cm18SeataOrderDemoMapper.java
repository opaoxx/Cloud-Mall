package com.cloudmall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.order.domain.po.Cm18SeataOrderDemoPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** CM-18 Seata order 演练表数据访问接口。 */
@Mapper
public interface Cm18SeataOrderDemoMapper extends BaseMapper<Cm18SeataOrderDemoPO> {
  /** 全局提交已由 Seata TM 确认后，单独标记演练行可供幂等读取。 */
  @Update("UPDATE seata_cm18_order_demo SET status='COMMITTED'"
      + " WHERE tx_id=#{txId} AND status='CREATED'")
  int markCommitted(@Param("txId") String txId);
}
