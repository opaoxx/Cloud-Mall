package com.cloudmall.order.controller;

import com.cloudmall.order.service.impl.OrderServiceImpl;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 暂存尚未迁移的订单消息监听器；HTTP入口已独立，消息拆分后移除继承。 */
@RestController
@RequestMapping("/api")
public class OrderController extends OrderServiceImpl {}
