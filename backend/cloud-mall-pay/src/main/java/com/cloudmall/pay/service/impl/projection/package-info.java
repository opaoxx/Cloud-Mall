/**
 * 将已核验的TCC回滚事实投影为公开支付FAILED状态。
 *
 * <p>入口暂停调用者本地事务，先读同库RR证明，再在事务外查询User，最后独立提交本地投影。 后台路径重核原token与lease，不能降级为公开无租约路径；本包不发起资金决议、取消或退款。
 */
package com.cloudmall.pay.service.impl.projection;
