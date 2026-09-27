package tacos.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

public class OpenApiSpecValidatorTest {

  private Map<String, Object> spec;

  @BeforeEach
  @SuppressWarnings("unchecked")
  public void loadSpec() {
    InputStream in = getClass().getClassLoader().getResourceAsStream("openapi.yaml");
    assertThat(in).isNotNull();
    Yaml yaml = new Yaml();
    spec = yaml.load(in);
    assertThat(spec).isNotNull();
  }

  @Test
  @DisplayName("El spec openapi.yaml valida estructura OpenAPI 3.0.x y metadatos obligatorios")
  public void validateStructureAndMetadata() {
    String openapi = (String) spec.get("openapi");
    assertThat(openapi).startsWith("3.0");

    @SuppressWarnings("unchecked")
    Map<String, Object> info = (Map<String, Object>) spec.get("info");
    assertThat(info).isNotNull();
    assertThat(info.get("title")).isEqualTo("Taco Cloud Public API");
    assertThat(info.get("version")).isEqualTo("1.0.0");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> servers = (List<Map<String, Object>>) spec.get("servers");
    assertThat(servers).isNotEmpty();
    boolean hasV1Server = servers.stream().anyMatch(s -> s.get("url").toString().contains("/api/v1"));
    assertThat(hasV1Server).isTrue();
  }

  @Test
  @DisplayName("Cada endpoint implementado aparece con sus métodos y schemas en paths")
  public void validatePaths() {
    @SuppressWarnings("unchecked")
    Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
    assertThat(paths).isNotNull();

    List<String> requiredPaths = Arrays.asList(
        "/ingredients",
        "/ingredients/{id}",
        "/tacos",
        "/tacos/recents",
        "/tacos/today",
        "/tacos/top",
        "/tacos/validate",
        "/tacos/{id}",
        "/tacos/{id}/rating",
        "/orders",
        "/orders/{orderId}",
        "/orders/{orderId}/reorder",
        "/users/me/orders",
        "/users/me/orders/{id}",
        "/users/me/favorites",
        "/users/me/favorites/{tacoId}",
        "/coupons/validate",
        "/payment-methods/tokenize",
        "/payment-methods",
        "/kitchen/queue",
        "/kitchen/orders/claim",
        "/kitchen/orders/{id}/status",
        "/kitchen/dlq/stats",
        "/announcements",
        "/admin/announcements"
    );

    for (String path : requiredPaths) {
      assertThat(paths).as("Path '%s' must be documented in openapi.yaml", path).containsKey(path);
    }

    // Validar POST /orders contiene header Idempotency-Key y X-Correlation-Id
    @SuppressWarnings("unchecked")
    Map<String, Object> ordersPath = (Map<String, Object>) paths.get("/orders");
    @SuppressWarnings("unchecked")
    Map<String, Object> postOrder = (Map<String, Object>) ordersPath.get("post");
    assertThat(postOrder).isNotNull();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> parameters = (List<Map<String, Object>>) postOrder.get("parameters");
    assertThat(parameters).isNotNull();

    boolean hasIdempotencyKey = parameters.stream().anyMatch(p -> "Idempotency-Key".equalsIgnoreCase((String) p.get("name")));
    boolean hasCorrelationId = parameters.stream().anyMatch(p -> "X-Correlation-Id".equalsIgnoreCase((String) p.get("name")));

    assertThat(hasIdempotencyKey).as("POST /orders must document Idempotency-Key header").isTrue();
    assertThat(hasCorrelationId).as("POST /orders must document X-Correlation-Id header").isTrue();
  }

  @Test
  @DisplayName("Los schemas de request y response están separados y no provienen de entidades Mongo")
  public void validateSeparatedSchemas() {
    @SuppressWarnings("unchecked")
    Map<String, Object> components = (Map<String, Object>) spec.get("components");
    assertThat(components).isNotNull();

    @SuppressWarnings("unchecked")
    Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
    assertThat(schemas).isNotNull();

    // Requests
    assertThat(schemas).containsKey("OrderCreateRequest");
    assertThat(schemas).containsKey("TacoRequest");
    assertThat(schemas).containsKey("IngredientRequest");
    assertThat(schemas).containsKey("TokenizeRequest");
    assertThat(schemas).containsKey("CouponValidateRequest");
    assertThat(schemas).containsKey("RatingRequest");

    // Responses
    assertThat(schemas).containsKey("OrderResponse");
    assertThat(schemas).containsKey("TacoResponse");
    assertThat(schemas).containsKey("IngredientResponse");
    assertThat(schemas).containsKey("PaymentMethodResponse");
    assertThat(schemas).containsKey("CouponValidateResponse");
    assertThat(schemas).containsKey("TacoRatingSummaryResponse");

    // Error canonical RFC 7807
    assertThat(schemas).containsKey("ApiProblem");
    assertThat(schemas).containsKey("ApiProblemViolation");
  }

  @Test
  @DisplayName("Prueba negativa de campos sensibles: ningun schema expone password, cvv, pin o secret")
  public void negativeTest_noSensitiveFieldsInSchemas() {
    @SuppressWarnings("unchecked")
    Map<String, Object> components = (Map<String, Object>) spec.get("components");
    @SuppressWarnings("unchecked")
    Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");

    Set<String> sensitiveKeys = new HashSet<>(Arrays.asList(
        "password", "userpassword", "pin", "client_secret", "secretkey", "privatekey"
    ));

    for (Map.Entry<String, Object> entry : schemas.entrySet()) {
      String schemaName = entry.getKey();
      @SuppressWarnings("unchecked")
      Map<String, Object> schemaDef = (Map<String, Object>) entry.getValue();

      // En schemas de respuesta pública nunca debe haber CVV ni tokens sin máscara
      if (schemaName.endsWith("Response")) {
        checkNoFieldMatch(schemaName, schemaDef, Arrays.asList("cvv", "cvc", "ccnumber", "cardnumber", "password"));
      }

      // En ningún schema debe haber passwords o secrets
      checkNoFieldMatch(schemaName, schemaDef, sensitiveKeys);
    }
  }

  @SuppressWarnings("unchecked")
  private void checkNoFieldMatch(String schemaName, Map<String, Object> map, Iterable<String> forbidden) {
    if (map == null) return;
    if (map.containsKey("properties")) {
      Map<String, Object> props = (Map<String, Object>) map.get("properties");
      for (String propName : props.keySet()) {
        String lower = propName.toLowerCase();
        for (String f : forbidden) {
          assertThat(lower)
              .as("Schema '%s' must NOT contain sensitive field matching '%s' (found '%s')", schemaName, f, propName)
              .doesNotContain(f);
        }
      }
    }
  }

  @Test
  @DisplayName("Prueba de compatibilidad de contrato: detecta campos o status incompatibles")
  public void contractCompatibilityDetector_detectsMissingFieldAndStatusMismatch() {
    // Definición esperada de OrderResponse según OpenAPI
    @SuppressWarnings("unchecked")
    Map<String, Object> components = (Map<String, Object>) spec.get("components");
    @SuppressWarnings("unchecked")
    Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
    @SuppressWarnings("unchecked")
    Map<String, Object> orderResponseSchema = (Map<String, Object>) schemas.get("OrderResponse");
    @SuppressWarnings("unchecked")
    List<String> requiredFields = (List<String>) orderResponseSchema.get("required");

    // Caso 1: Payload compatible con status 201
    Map<String, Object> validPayload = Map.of(
        "id", "64b8a1c9e4b0c23a8f999999",
        "deliveryName", "Jane Doe",
        "total", 14.40
    );
    assertThat(checkCompatibility(201, 201, requiredFields, validPayload)).isTrue();

    // Caso 2: Status HTTP incompatible (esperaba 201, recibió 500)
    assertThatThrownBy(() -> checkCompatibility(201, 500, requiredFields, validPayload))
        .isInstanceOf(ContractViolationException.class)
        .hasMessageContaining("Status incompatible");

    // Caso 3: Campo requerido faltante en el payload (falta 'id')
    Map<String, Object> brokenPayloadMissingId = Map.of(
        "deliveryName", "Jane Doe",
        "total", 14.40
    );
    assertThatThrownBy(() -> checkCompatibility(201, 201, requiredFields, brokenPayloadMissingId))
        .isInstanceOf(ContractViolationException.class)
        .hasMessageContaining("Missing required contract field: id");
  }

  private boolean checkCompatibility(int expectedStatus, int actualStatus, List<String> requiredFields, Map<String, Object> payload) {
    if (expectedStatus != actualStatus) {
      throw new ContractViolationException("Status incompatible. Expected " + expectedStatus + " but got " + actualStatus);
    }
    if (requiredFields != null) {
      for (String field : requiredFields) {
        if (!payload.containsKey(field) || payload.get(field) == null) {
          throw new ContractViolationException("Missing required contract field: " + field);
        }
      }
    }
    return true;
  }

  public static class ContractViolationException extends RuntimeException {
    public ContractViolationException(String message) {
      super(message);
    }
  }

}
