package tacos.web.api;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.pricing.CouponService;
import tacos.pricing.CouponValidationResult;
import tacos.web.api.dto.CouponValidateRequest;
import tacos.web.api.dto.CouponValidateResponse;

@RestController
@RequestMapping(path = "/api/coupons", produces = "application/json")
public class CouponController {

  private final CouponService couponService;

  @Autowired
  public CouponController(CouponService couponService) {
    this.couponService = couponService;
  }

  @PostMapping(path = "/validate", consumes = "application/json")
  public Mono<CouponValidateResponse> validateCoupon(@Valid @RequestBody CouponValidateRequest request) {
    CouponValidationResult result = couponService.validateAndCalculate(request.getCode(), request.getSubtotal());

    CouponValidateResponse response = CouponValidateResponse.builder()
        .valid(result.isValid())
        .code(result.getCode())
        .status(result.getStatus().name())
        .message(result.getMessage())
        .discountType(result.getDiscountType())
        .discountValue(result.getDiscountValue())
        .discountAmount(result.getDiscountAmount())
        .subtotal(result.getSubtotal())
        .finalTotal(result.getFinalTotal())
        .currency("USD")
        .build();

    return Mono.just(response);
  }
}
