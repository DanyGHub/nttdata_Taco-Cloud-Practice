package tacos.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class OrderEventSerializationTest {

  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    df.setTimeZone(TimeZone.getTimeZone("UTC"));
    objectMapper.setDateFormat(df);
  }

  @Test
  @DisplayName("Debe serializar y deserializar OrderEvent V1 correctamente (round-trip)")
  public void testSerializationAndDeserializationV1() throws Exception {
    OrderEventIngredientPayload ing1 = OrderEventIngredientPayload.builder()
        .name("Flour Tortilla")
        .type("WRAP")
        .build();
    OrderEventIngredientPayload ing2 = OrderEventIngredientPayload.builder()
        .name("Carnitas")
        .type("PROTEIN")
        .build();

    OrderEventItemPayload taco1 = OrderEventItemPayload.builder()
        .tacoName("Carnitas Taco")
        .quantity(2)
        .ingredients(Arrays.asList(ing1, ing2))
        .build();

    OrderEventPayload payload = OrderEventPayload.builder()
        .orderId("ORD-12345")
        .status("CREATED")
        .customerName("John Doe")
        .deliveryStreet("123 Main St")
        .deliveryCity("Austin")
        .deliveryState("TX")
        .deliveryZip("78701")
        .placedAt(new Date())
        .items(Collections.singletonList(taco1))
        .stationId("STATION_1")
        .cookId("chef1")
        .estimatedPrepMinutes(7)
        .build();

    OrderEvent originalEvent = OrderEvent.of(OrderEventType.ORDER_CREATED, "ORD-12345", payload);

    String json = objectMapper.writeValueAsString(originalEvent);
    assertThat(json).isNotNull();
    assertThat(json).contains("\"eventType\":\"ORDER_CREATED\"");
    assertThat(json).contains("\"version\":1");
    assertThat(json).contains("\"correlationId\":\"ORD-12345\"");
    assertThat(json).contains("\"orderId\":\"ORD-12345\"");
    assertThat(json).contains("\"tacoName\":\"Carnitas Taco\"");

    OrderEvent deserialized = objectMapper.readValue(json, OrderEvent.class);
    assertThat(deserialized).isNotNull();
    assertThat(deserialized.getEventId()).isEqualTo(originalEvent.getEventId());
    assertThat(deserialized.getEventType()).isEqualTo(OrderEventType.ORDER_CREATED);
    assertThat(deserialized.getVersion()).isEqualTo(1);
    assertThat(deserialized.getCorrelationId()).isEqualTo("ORD-12345");
    assertThat(deserialized.getPayload().getOrderId()).isEqualTo("ORD-12345");
    assertThat(deserialized.getPayload().getCustomerName()).isEqualTo("John Doe");
    assertThat(deserialized.getPayload().getDeliveryName()).isEqualTo("John Doe");
    assertThat(deserialized.getPayload().getItems()).hasSize(1);
    assertThat(deserialized.getPayload().getTacos()).hasSize(1);
    assertThat(deserialized.getPayload().getItems().get(0).getTacoName()).isEqualTo("Carnitas Taco");
    assertThat(deserialized.getPayload().getItems().get(0).getName()).isEqualTo("Carnitas Taco");
    assertThat(deserialized.getPayload().getItems().get(0).getIngredients()).hasSize(2);
  }

  @Test
  @DisplayName("Compatibilidad hacia adelante: consumidor V1 debe ignorar campos desconocidos sin fallar")
  public void testForwardCompatibilityWithUnknownFields() throws Exception {
    String futureV2Json = "{\n" +
        "  \"eventId\": \"" + UUID.randomUUID().toString() + "\",\n" +
        "  \"eventType\": \"ORDER_CREATED\",\n" +
        "  \"version\": 2,\n" +
        "  \"occurredAt\": \"2026-09-21T06:00:00.000Z\",\n" +
        "  \"correlationId\": \"ORD-V2-999\",\n" +
        "  \"droneDeliveryId\": \"DRONE-X9\",\n" + // Campo nuevo en la raíz
        "  \"futureFlag\": true,\n" +
        "  \"payload\": {\n" +
        "    \"orderId\": \"ORD-V2-999\",\n" +
        "    \"status\": \"CREATED\",\n" +
        "    \"customerName\": \"Future Customer\",\n" +
        "    \"vipTier\": \"PLATINUM\",\n" + // Campo nuevo en payload
        "    \"items\": [\n" +
        "      {\n" +
        "        \"tacoName\": \"Cyber Taco\",\n" +
        "        \"quantity\": 1,\n" +
        "        \"calorieCount\": 450,\n" + // Campo nuevo en taco
        "        \"ingredients\": [\n" +
        "          {\"name\": \"Synthetic Wrap\", \"origin\": \"Lab\"}\n" + // Campo nuevo en ingrediente
        "        ]\n" +
        "      }\n" +
        "    ]\n" +
        "  }\n" +
        "}";

    OrderEvent deserialized = objectMapper.readValue(futureV2Json, OrderEvent.class);
    assertThat(deserialized).isNotNull();
    assertThat(deserialized.getEventType()).isEqualTo(OrderEventType.ORDER_CREATED);
    assertThat(deserialized.getCorrelationId()).isEqualTo("ORD-V2-999");
    assertThat(deserialized.getPayload()).isNotNull();
    assertThat(deserialized.getPayload().getCustomerName()).isEqualTo("Future Customer");
    assertThat(deserialized.getPayload().getItems()).hasSize(1);
    assertThat(deserialized.getPayload().getItems().get(0).getTacoName()).isEqualTo("Cyber Taco");
  }

  @Test
  @DisplayName("Sanitización estricta: el JSON emitido no debe contener datos sensibles de pago ni Mongo")
  public void testZeroSensitiveDataInEvent() throws Exception {
    OrderEventPayload payload = OrderEventPayload.builder()
        .orderId("ORD-SAFE-1")
        .status("CREATED")
        .customerName("Alice Smith")
        .deliveryStreet("456 Elm St")
        .deliveryCity("Dallas")
        .deliveryState("TX")
        .deliveryZip("75001")
        .placedAt(new Date())
        .build();

    OrderEvent event = OrderEvent.of(OrderEventType.ORDER_CREATED, "ORD-SAFE-1", payload);
    String json = objectMapper.writeValueAsString(event);

    assertThat(json).doesNotContain("ccNumber");
    assertThat(json).doesNotContain("paymentToken");
    assertThat(json).doesNotContain("ccCVV");
    assertThat(json).doesNotContain("cvv");
    assertThat(json).doesNotContain("ccExpiration");
    assertThat(json).doesNotContain("password");
    assertThat(json).doesNotContain("brand");
    assertThat(json).doesNotContain("last4");
    assertThat(json).doesNotContain("_class");
    assertThat(json).doesNotContain("@id");
  }

  @Test
  @DisplayName("Integridad de metadatos del evento: UUID, versión, timestamp y correlación")
  public void testEventMetadataIntegrity() {
    OrderEvent event = OrderEvent.of(
        OrderEventType.ORDER_STATUS_CHANGED,
        "CORR-123",
        OrderEventPayload.builder().orderId("CORR-123").status("PREPARING").previousStatus("ACCEPTED").build()
    );

    assertThat(event.getEventId()).isNotNull();
    assertThat(UUID.fromString(event.getEventId())).isNotNull(); // Valida formato UUID
    assertThat(event.getVersion()).isEqualTo(1);
    assertThat(event.getOccurredAt()).isNotNull();
    assertThat(event.getCorrelationId()).isEqualTo("CORR-123");
    assertThat(event.getEventType()).isEqualTo(OrderEventType.ORDER_STATUS_CHANGED);
    assertThat(event.getPayload().getPreviousStatus()).isEqualTo("ACCEPTED");
    assertThat(event.getPayload().getStatus()).isEqualTo("PREPARING");
  }

  @Test
  @DisplayName("Soporta las variantes ORDER_CREATED, ORDER_STATUS_CHANGED y ORDER_CANCELLED")
  public void testAllEventTypesSupported() throws Exception {
    for (OrderEventType type : OrderEventType.values()) {
      OrderEvent event = OrderEvent.of(
          type,
          "ORDER-TYPE-TEST",
          OrderEventPayload.builder().orderId("ORDER-TYPE-TEST").build()
      );
      String json = objectMapper.writeValueAsString(event);
      OrderEvent parsed = objectMapper.readValue(json, OrderEvent.class);
      assertThat(parsed.getEventType()).isEqualTo(type);
    }
  }

}
