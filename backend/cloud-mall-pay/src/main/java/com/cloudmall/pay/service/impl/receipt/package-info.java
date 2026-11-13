/**
 * LocalTCC资源回执及执行所需的资源事实校验。
 *
 * <p>独立CLAIMED认领、官方Fence同事务回调、永久终态重放和状态/分支校验集中在本包。
 * ExecutionEvidence核对回执、Fence与传入的User镜像，保留原决议含义；它不属于只读诊断evidence包。
 */
package com.cloudmall.pay.service.impl.receipt;
