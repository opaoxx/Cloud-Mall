package com.cloudmall.user.controller;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.user.domain.dto.AddressRequestDTO;
import com.cloudmall.user.domain.dto.BalanceDebitRequestDTO;
import com.cloudmall.user.domain.dto.BalanceRefundRequestDTO;
import com.cloudmall.user.domain.dto.CredentialsDTO;
import com.cloudmall.user.domain.dto.DebitRequestDTO;
import com.cloudmall.user.domain.dto.ProfileDTO;
import com.cloudmall.user.service.AuthenticationService;
import com.cloudmall.user.service.BalanceLedgerService;
import com.cloudmall.user.service.UserAddressService;
import com.cloudmall.user.service.UserProfileService;
import javax.validation.Valid;
import org.springframework.context.annotation.DependsOn;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户HTTP接口：绑定与校验请求、读取身份并委托业务服务。 */
@RestController
@RequestMapping("/api")
@DependsOn("userAccountInitializer")
public class UserController {
  /** 注册、登录与登录态失效。 */
  private final AuthenticationService authenticationService;

  /** 公开用户资料读写。 */
  private final UserProfileService userProfileService;

  /** 所属收货地址维护。 */
  private final UserAddressService userAddressService;

  /** 持久化余额操作与证明。 */
  private final BalanceLedgerService balanceLedgerService;

  /** 注入四项独立业务能力。 */
  public UserController(
      AuthenticationService authenticationService,
      UserProfileService userProfileService,
      UserAddressService userAddressService,
      BalanceLedgerService balanceLedgerService) {
    this.authenticationService = authenticationService;
    this.userProfileService = userProfileService;
    this.userAddressService = userAddressService;
    this.balanceLedgerService = balanceLedgerService;
  }

  /** 注册用户并返回用户标识。 */
  @PostMapping("/auth/register")
  public ApiResponse<?> register(@Valid @RequestBody CredentialsDTO credentials) {
    return ApiResponse.ok(authenticationService.register(credentials));
  }

  /** 校验凭据并创建 Redis 登录态。 */
  @PostMapping("/auth/login")
  public ApiResponse<?> login(@Valid @RequestBody CredentialsDTO credentials) {
    return ApiResponse.ok(authenticationService.login(credentials));
  }

  /** 使当前登录 Token 失效。 */
  @PostMapping("/auth/logout")
  public ApiResponse<?> logout(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    authenticationService.logout(authorization);
    return ApiResponse.ok(null);
  }

  /** 查询当前用户资料。 */
  @GetMapping("/users/me")
  public ApiResponse<?> me() {
    return ApiResponse.ok(userProfileService.me(AuthContext.requireUserId()));
  }

  /** 更新当前用户允许修改的资料。 */
  @PutMapping("/users/me")
  public ApiResponse<?> update(@RequestBody ProfileDTO profile) {
    return ApiResponse.ok(userProfileService.update(AuthContext.requireUserId(), profile));
  }

  /** 查询当前用户的收货地址。 */
  @GetMapping("/users/me/addresses")
  public ApiResponse<?> addresses() {
    return ApiResponse.ok(userAddressService.addresses(AuthContext.requireUserId()));
  }

  /** 新增当前用户收货地址。 */
  @PostMapping("/users/me/addresses")
  public ApiResponse<?> addAddress(@Valid @RequestBody AddressRequestDTO request) {
    return ApiResponse.ok(userAddressService.addAddress(AuthContext.requireUserId(), request));
  }

  /** 更新当前用户收货地址。 */
  @PutMapping("/users/me/addresses/{id}")
  public ApiResponse<?> updateAddress(
      @PathVariable("id") Long addressId, @Valid @RequestBody AddressRequestDTO request) {
    return ApiResponse.ok(
        userAddressService.updateAddress(AuthContext.requireUserId(), addressId, request));
  }

  /** 删除当前用户收货地址。 */
  @DeleteMapping("/users/me/addresses/{id}")
  public ApiResponse<?> deleteAddress(@PathVariable("id") Long addressId) {
    userAddressService.deleteAddress(AuthContext.requireUserId(), addressId);
    return ApiResponse.ok(null);
  }

  /** 按幂等支付键扣减用户余额。 */
  @PostMapping("/internal/users/{userId}/balance/debit")
  public ApiResponse<?> debit(
      @PathVariable Long userId,
      @RequestHeader(value = "X-User-Id", required = false) Long callerId,
      @Valid @RequestBody DebitRequestDTO request) {
    return balanceLedgerService.debit(userId, callerId, request);
  }

  /** 按持久化操作键执行余额扣款。 */
  @PostMapping("/internal/users/{userId}/balance/debits")
  public ApiResponse<?> debitInternal(
      @PathVariable Long userId,
      @RequestHeader(value = "X-User-Id", required = false) Long callerId,
      @Valid @RequestBody BalanceDebitRequestDTO request) {
    return balanceLedgerService.debitInternal(userId, callerId, request);
  }

  /** 在同一只读快照证明主账本、原扣款及关联/pay双集合，供支付恢复判断事实。 */
  @GetMapping("/internal/users/{userId}/balance/operations/{operationKey}")
  public ApiResponse<?> balanceOperation(
      @PathVariable Long userId,
      @PathVariable String operationKey,
      @RequestHeader(value = "X-User-Id", required = false) Long callerId) {
    return balanceLedgerService.balanceOperation(userId, operationKey, callerId);
  }

  /** 按唯一退款键恢复已应用扣款。 */
  @PostMapping("/internal/users/{userId}/balance/refunds")
  public ApiResponse<?> refundInternal(
      @PathVariable Long userId,
      @RequestHeader(value = "X-User-Id", required = false) Long callerId,
      @Valid @RequestBody BalanceRefundRequestDTO request) {
    return balanceLedgerService.refundInternal(userId, callerId, request);
  }
}
