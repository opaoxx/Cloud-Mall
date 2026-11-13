/**
 * TCC只读取证：入口门、本地RR快照与事务外远端GET核对。
 *
 * <p>各诊断开关默认关闭；报告仅描述观察事实，不能授权资金写入、交付或恢复。两个ReadService保持独立Spring事务代理，
 * 远端采集前后分别读取本地快照；Account和LinkedRefund的包内校验由本包复用。
 */
package com.cloudmall.pay.service.impl.evidence;
