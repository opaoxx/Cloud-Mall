/**
 * User只读取证实现，覆盖基础账户、关联退款、当前及历史冻结归属和同支付账本。
 *
 * <p>入口Service在事务与连接之前验证开关、凭证和上下文，ReadService在同一只读RR快照内
 * 读取并分类持久证据。缺证不代表资金动作未发生；本包不注册RM、不修改资金、不调用TC或恢复流程。
 */
package com.cloudmall.user.service.impl.evidence;
