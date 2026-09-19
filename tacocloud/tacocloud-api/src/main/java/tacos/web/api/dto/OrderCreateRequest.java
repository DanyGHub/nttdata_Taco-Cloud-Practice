package tacos.web.api.dto;

import java.util.ArrayList;
import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import org.hibernate.validator.constraints.CreditCardNumber;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderCreateRequest {

  @NotBlank(message = "Delivery name is required")
  private String deliveryName;

  @NotBlank(message = "Street is required")
  private String deliveryStreet;

  @NotBlank(message = "City is required")
  private String deliveryCity;

  @NotBlank(message = "State is required")
  @Size(min = 2, message = "State must be at least 2 characters")
  private String deliveryState;

  @NotBlank(message = "Zip code is required")
  private String deliveryZip;

  private String paymentMethodId;
  private String paymentToken;
  private String brand;
  private String last4;

  @CreditCardNumber(message = "Not a valid credit card number")
  private String ccNumber;

  @Pattern(regexp = "^(0[1-9]|1[0-2])([\\/])([2-9][0-9])$", message = "Must be formatted MM/YY")
  private String ccExpiration;

  @Pattern(regexp = "^[0-9]{3}$", message = "Invalid CVV")
  private String ccCVV;

  @NotEmpty(message = "You must specify at least 1 taco")
  @Valid
  private List<TacoRequest> tacos = new ArrayList<>();

  @Valid
  private List<OrderItemRequest> items = new ArrayList<>();

  private java.math.BigDecimal total;
  private java.math.BigDecimal subtotal;

  public void setItems(List<OrderItemRequest> items) {
    this.items = items;
    if (items != null) {
      for (OrderItemRequest item : items) {
        if (item != null && item.getTaco() != null) {
          this.tacos.add(item.getTaco());
        }
      }
    }
  }

  public void addTaco(TacoRequest taco) {
    this.tacos.add(taco);
  }

  public void addItem(OrderItemRequest item) {
    this.items.add(item);
    if (item != null && item.getTaco() != null) {
      this.tacos.add(item.getTaco());
    }
  }

  @Override
  public String toString() {
    String maskedCard = (ccNumber != null && ccNumber.length() >= 4)
        ? "****-****-****-" + ccNumber.substring(ccNumber.length() - 4)
        : (last4 != null ? "****-****-****-" + last4 : "****");
    return "OrderCreateRequest(deliveryName=" + deliveryName + ", deliveryStreet=" + deliveryStreet
        + ", deliveryCity=" + deliveryCity + ", deliveryState=" + deliveryState + ", deliveryZip=" + deliveryZip
        + ", paymentMethodId=" + paymentMethodId + ", brand=" + brand + ", last4=" + last4
        + ", ccNumber=" + maskedCard + ", ccCVV=***, tacos=" + tacos + ")";
  }

}
