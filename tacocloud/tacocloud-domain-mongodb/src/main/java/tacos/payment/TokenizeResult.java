package tacos.payment;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenizeResult {
  private String paymentToken;
  private String brand;
  private String last4;
  private String expiration;
}
