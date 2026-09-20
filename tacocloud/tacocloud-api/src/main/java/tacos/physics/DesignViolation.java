package tacos.physics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DesignViolation {

  private String code;
  private String message;
  private String field;

  public static DesignViolation of(String code, String message) {
    return new DesignViolation(code, message, "ingredients");
  }

  public static DesignViolation of(String code, String message, String field) {
    return new DesignViolation(code, message, field);
  }
}
