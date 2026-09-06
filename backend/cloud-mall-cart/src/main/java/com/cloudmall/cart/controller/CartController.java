package com.cloudmall.cart.controller;

import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.impl.CartServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 购物车 HTTP 接口层，负责暴露购物车请求映射。 */
@RestController
@RequestMapping("/api/cart")
public class CartController extends CartServiceImpl {
  /** 创建购物车接口控制器。 */
  public CartController(
      StringRedisTemplate redis, ObjectMapper mapper, ProductClient productClient) {
    super(redis, mapper, productClient);
  }
}
