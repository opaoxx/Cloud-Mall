package com.cloudmall.order.controller;

import com.cloudmall.order.service.impl.OrderServiceImpl;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 订单 HTTP 接口层，负责暴露既有订单请求映射。 */
@RestController
@RequestMapping("/api")
public class OrderController extends OrderServiceImpl {}
