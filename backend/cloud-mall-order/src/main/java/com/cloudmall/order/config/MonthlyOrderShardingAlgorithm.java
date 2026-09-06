package com.cloudmall.order.config;

import org.apache.shardingsphere.api.sharding.standard.PreciseShardingAlgorithm;
import org.apache.shardingsphere.api.sharding.standard.PreciseShardingValue;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collection;

/** Routes all order-domain tables by the DATETIME(3) created_at month. */
public final class MonthlyOrderShardingAlgorithm implements PreciseShardingAlgorithm<Comparable<?>> {
    @Override
    public String doSharding(Collection<String> targets, PreciseShardingValue<Comparable<?>> value) {
        Object raw = value.getValue();
        YearMonth month;
        if (raw instanceof LocalDateTime) month = YearMonth.from((LocalDateTime) raw);
        else month = YearMonth.parse(String.valueOf(raw).substring(0, 7));
        String suffix = "_" + month.toString().replace("-", "");
        return targets.stream().filter(t -> t.endsWith(suffix)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("订单分片不存在: " + suffix));
    }
}
