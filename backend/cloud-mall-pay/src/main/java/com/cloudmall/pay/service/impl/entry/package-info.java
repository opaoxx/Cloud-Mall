/**
 * TCC公开支付与失败请求路由，保留原执行、永久取消gate与未知结果语义。
 *
 * <p>pay不新增事务，已有执行只恢复原决议；failure暂停调用方本地事务后按资金事实分流。 实际执行、交付、回滚投影及取消由各自子包管理，公开入口不新增重试或资金写入。
 */
package com.cloudmall.pay.service.impl.entry;
