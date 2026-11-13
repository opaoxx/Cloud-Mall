/**
 * TCC一次性执行协调、原执行事实与有界恢复调度。
 *
 * <p>执行权、XID、决议和恢复租约由独立事务提交；恢复只能核对原事实及重试原方向，不能重新begin或接管Try权限。
 * 支付状态投影通过原公开服务连接，批量/预算/轮换/退避和上下文约束保持原实现。
 */
package com.cloudmall.pay.service.impl.execution;
