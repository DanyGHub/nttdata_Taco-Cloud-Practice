package tacos.messaging;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload seguro para eventos de orden.
 * Contiene únicamente datos de preparación y entrega.
 * Prohibido incluir PAN, CVV, tokens de pago, contraseñas o entidades MongoDB.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderEventPayload {

  private String orderId;
  private String status;
  private String customerName;
  private String deliveryStreet;
  private String deliveryCity;
  private String deliveryState;
  private String deliveryZip;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
  private Date placedAt;

  @Builder.Default
  private List<OrderEventItemPayload> items = new ArrayList<>();

  // Metadatos operacionales de cocina
  private String stationId;
  private String cookId;
  private Integer estimatedPrepMinutes;

  // Metadatos de ciclo de vida / workflow
  private String previousStatus;
  private String cancellationReason;

  /**
   * Alias de compatibilidad para clientes de cocina o Thymeleaf (deliveryName).
   */
  public String getDeliveryName() {
    return customerName;
  }

  public void setDeliveryName(String deliveryName) {
    this.customerName = deliveryName;
  }

  /**
   * Alias de compatibilidad para clientes de cocina o Thymeleaf (tacos).
   */
  public List<OrderEventItemPayload> getTacos() {
    return items;
  }

  public void setTacos(List<OrderEventItemPayload> tacos) {
    this.items = tacos;
  }

}
