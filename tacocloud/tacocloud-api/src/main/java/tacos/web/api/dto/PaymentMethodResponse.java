package tacos.web.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodResponse {

  private String id;
  private String brand;
  private String last4;
  private String expiration;
  private String maskedToken;
  private String username;

}
