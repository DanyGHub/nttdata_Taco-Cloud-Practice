package tacos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import tacos.web.api.dto.OrderCreateRequest;
import tacos.web.api.dto.OrderItemRequest;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.TacoRequest;

/**
 * Prueba de integración con runtime MVC real en puerto aleatorio.
*/
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class FullStackRuntimeRandomPortIntegrationTest {

  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate restTemplate;

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  @Test
  @DisplayName("TC-36/TC-35: Runtime real sirve contrato OpenAPI en /openapi.yaml")
  public void runtimeReal_servesOpenApiSpec() {
    ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/openapi.yaml", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType().toString()).contains("text/yaml");
    assertThat(response.getBody()).contains("openapi: 3.0.3");
    assertThat(response.getBody()).contains("Taco Cloud Public API");
    assertThat(response.getBody()).contains("/api/v1");
  }

  @Test
  @DisplayName("TC-36/TC-35: Runtime real responde catálogo v1 en /api/v1/ingredients sin deprecación")
  public void runtimeReal_servesV1IngredientsWithoutDeprecation() {
    ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/api/v1/ingredients", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getHeaders().get("Deprecation")).isNull();
    assertThat(response.getBody()).isNotBlank();
  }

  @Test
  @DisplayName("TC-36/TC-35: Runtime real responde ruta legada /api/ingredients con cabeceras RFC 8594 de deprecación")
  public void runtimeReal_servesLegacyIngredientsWithDeprecationHeaders() {
    ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/api/ingredients", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    // Valida cabeceras de deprecación
    assertThat(response.getHeaders().getFirst("Deprecation")).isEqualTo("true");
    assertThat(response.getHeaders().getFirst("Sunset")).isNotBlank();
    assertThat(response.getHeaders().getFirst("Link")).contains("</api/v1/ingredients>; rel=\"successor-version\"");
    assertThat(response.getHeaders().getFirst("Warning")).contains("deprecated");
  }

  @Test
  @DisplayName("TC-36/TC-11: Creación de pedido /api/v1/orders sin credenciales es rechazada (redirección a login o 401)")
  public void runtimeReal_postOrderWithoutAuth_returns401OrRedirect() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> entity = new HttpEntity<>("{}", headers);

    ResponseEntity<String> response = restTemplate.postForEntity(baseUrl() + "/api/v1/orders", entity, String.class);

    // Spring Security con formLogin() redirige a /login (302 Found) o rechaza con 401/403
    assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.FOUND);
    if (response.getStatusCode() == HttpStatus.FOUND) {
      assertThat(response.getHeaders().getLocation().toString()).contains("/login");
    }
  }

  @Test
  @DisplayName("TC-36/TC-31/TC-34: Creación de pedido con autenticación, Correlation ID e Idempotency-Key exitosa")
  public void runtimeReal_postOrderWithAuthAndHeaders_returns201WithCorrelationId() {
    TacoRequest taco = new TacoRequest();
    taco.setName("Carnitas Runtime");
    taco.setIngredientIds(Arrays.asList("FLTO", "CARN"));
    OrderItemRequest item = new OrderItemRequest();
    item.setTaco(taco);
    item.setQuantity(1);

    OrderCreateRequest req = new OrderCreateRequest();
    req.setDeliveryName("Runtime Customer");
    req.setDeliveryStreet("789 Server Way");
    req.setDeliveryCity("Austin");
    req.setDeliveryState("TX");
    req.setDeliveryZip("78701");
    req.setPaymentMethodId("pm-runtime-test");
    req.setItems(Arrays.asList(item));

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "runtime-test-key-999");
    headers.set("X-Correlation-Id", "corr-runtime-uuid-888");

    HttpEntity<OrderCreateRequest> entity = new HttpEntity<>(req, headers);

    // Llamada con credenciales básicas de usuario
    TestRestTemplate authenticatedClient = restTemplate.withBasicAuth("habuma", "password");
    ResponseEntity<OrderResponse> response = authenticatedClient.postForEntity(
        baseUrl() + "/api/v1/orders",
        entity,
        OrderResponse.class
    );

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getId()).isNotBlank();
    assertThat(response.getBody().getDeliveryName()).isEqualTo("Runtime Customer");

    // Verifica que el Correlation ID fue propagado en la respuesta HTTP
    assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-runtime-uuid-888");
  }

}
