package tacos.web.api.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CreditCardNumber;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenizeRequest {

  @NotBlank(message = "Card number is required")
  @CreditCardNumber(message = "Not a valid credit card number")
  private String cardNumber;

  @NotBlank(message = "Expiration date is required")
  @Pattern(regexp = "^(0[1-9]|1[0-2])([\\/])([2-9][0-9])$", message = "Must be formatted MM/YY")
  private String expiration;

  @NotBlank(message = "CVV is required")
  @Pattern(regexp = "^[0-9]{3,4}$", message = "Invalid CVV")
  private String cvv;

  private String cardholderName;

  @Override
  public String toString() {
    String maskedCard = (cardNumber != null && cardNumber.length() >= 4)
        ? "****-****-****-" + cardNumber.substring(cardNumber.length() - 4)
        : "****";
    return "TokenizeRequest(cardNumber=" + maskedCard + ", expiration=" + expiration + ", cvv=***, cardholderName=" + cardholderName + ")";
  }
}
