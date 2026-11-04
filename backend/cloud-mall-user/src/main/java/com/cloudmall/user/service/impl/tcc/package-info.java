/**
 * User TCC运行实现：本地资金状态机、显式Fence适配、一次性HTTP接收与状态读取。
 *
 * <p>RM和HTTP桥沿config中的既有开关装配。资源接口和action name属于稳定协议； Fence与资金写使用同一REQUIRED事务，接收记录使用独立事务，状态读取使用只读RR。
 * 取证Reader构造的状态查询委托复用调用方快照，不注册分支或触发清算。
 */
package com.cloudmall.user.service.impl.tcc;
