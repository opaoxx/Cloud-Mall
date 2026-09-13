package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudmall.user.domain.dto.AddressRequestDTO;
import javax.validation.Validation;
import javax.validation.Validator;
import org.junit.jupiter.api.Test;

class AddressValidationTest {
  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void acceptsOnlyElevenDigitMainlandMobileNumbers() {
    AddressRequestDTO validRequest = requestWithPhone("13800138000");
    AddressRequestDTO alphabeticRequest = requestWithPhone("1380013800a");
    AddressRequestDTO shortRequest = requestWithPhone("1380013800");

    assertTrue(validator.validate(validRequest).isEmpty());
    assertFalse(validator.validate(alphabeticRequest).isEmpty());
    assertFalse(validator.validate(shortRequest).isEmpty());
  }

  private AddressRequestDTO requestWithPhone(String phone) {
    AddressRequestDTO request = new AddressRequestDTO();
    request.receiver = "小明";
    request.phone = phone;
    request.detailAddress = "云购路 1 号";
    return request;
  }
}
