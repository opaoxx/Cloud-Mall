package com.cloudmall.user.controller;

import com.cloudmall.user.service.impl.UserServiceImpl;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户 HTTP 接口层，仅负责暴露用户领域服务的既有请求映射。 */
@RestController
@RequestMapping("/api")
public class UserController extends UserServiceImpl {}
