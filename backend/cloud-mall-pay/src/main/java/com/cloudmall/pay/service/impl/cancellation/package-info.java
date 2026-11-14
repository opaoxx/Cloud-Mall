/**
 * TCC无资金执行取消、未决执行回滚请求与已提交执行的取消方向登记。
 *
 * <p>三个入口分别保持原持久事实和事务边界；远端只在短事务之间调用。 取消恢复仅推进已有台账，已提交取消保留交付租约，未决中止不直接调用TC或投影FAILED。
 */
package com.cloudmall.pay.service.impl.cancellation;
