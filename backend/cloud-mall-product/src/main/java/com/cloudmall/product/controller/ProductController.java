package com.cloudmall.product.controller;

import com.cloudmall.product.service.impl.ProductServiceImpl;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 商品 HTTP 接口层，复用商品服务中既有的请求映射和返回契约。 */
@RestController
@RequestMapping("/api")
public class ProductController extends ProductServiceImpl {}
