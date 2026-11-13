/**
 * TCC派发桥及独立持久化：绑定原支付/XID、提交一次POST权限、核对并保存User结果。
 *
 * <p>Bridge通过另一Bean的REQUIRES_NEW方法提交本地事实，未知结果不返还POST权限，后续只查询原交易。包内sameAnchor同时约束写入与读回身份。
 */
package com.cloudmall.pay.service.impl.dispatch;
