package com.cloudmall.pay.controller;

import com.cloudmall.pay.service.impl.PayServiceImpl;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 支付 HTTP 接口层，保持支付接口的原有路径和请求映射。 */
@RestController
@RequestMapping("/api/payments")
public class PayController extends PayServiceImpl {}
