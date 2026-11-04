/**
 * 独立 Seata 库存演练实现，仅操作演练表。
 *
 * <p>编排服务调用独立本地事务代理，分支提交后才暂停或注入故障。本包排除在普通库存依赖熔断之外， 默认不启用；HTTP、配置和数据库中的 CM18 标识仍是原兼容协议。
 */
package com.cloudmall.stock.service.impl.seata;
