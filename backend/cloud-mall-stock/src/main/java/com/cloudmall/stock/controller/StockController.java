package com.cloudmall.stock.controller;

import com.cloudmall.stock.service.impl.StockServiceImpl;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 库存 HTTP 接口层，负责暴露内部库存调用契约。 */
@RestController
@RequestMapping("/api/stock")
public class StockController extends StockServiceImpl {}
