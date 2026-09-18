package tacos;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
public class SecurityAuthorizationTest {

  @Autowired
  private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  public void setUp() {
    mockMvc = MockMvcBuilders
        .webAppContextSetup(context)
        .apply(springSecurity())
        .build();
  }

  @Test
  @DisplayName("TC-11: Autenticación real HTTP Basic con admin/admin")
  public void httpBasic_admin_shouldAuthenticate() throws Exception {
    mockMvc.perform(get("/actuator/metrics").with(httpBasic("admin", "admin")))
        .andExpect(status().isOk());
  }

  // 1. Catálogo público

  @Test
  @DisplayName("TC-11: Anónimo puede consultar catálogo de ingredientes (200 OK)")
  public void getIngredients_anonymous_shouldReturn200() throws Exception {
    mockMvc.perform(get("/api/ingredients"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("TC-11: Anónimo puede consultar catálogo de tacos (200 OK)")
  public void getTacos_anonymous_shouldReturn200() throws Exception {
    mockMvc.perform(get("/api/tacos?recent"))
        .andExpect(status().isOk());
  }

  // 2. Administración de ingredientes (Sólo ADMIN)

  @Test
  @DisplayName("TC-11: Anónimo intentando crear ingrediente es rechazado con 401 Unauthorized")
  public void postIngredient_anonymous_shouldReturn401() throws Exception {
    String payload = "{\"id\":\"TEST\",\"name\":\"Test Ingredient\",\"type\":\"CHEESE\"}";
    mockMvc.perform(post("/api/ingredients")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("TC-11: USER intentando crear ingrediente es rechazado con 403 Forbidden")
  public void postIngredient_userRole_shouldReturn403() throws Exception {
    String payload = "{\"id\":\"TEST\",\"name\":\"Test Ingredient\",\"type\":\"CHEESE\"}";
    mockMvc.perform(post("/api/ingredients")
            .with(user("habuma").roles("USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("TC-11: KITCHEN intentando crear ingrediente es rechazado con 403 Forbidden")
  public void postIngredient_kitchenRole_shouldReturn403() throws Exception {
    String payload = "{\"id\":\"TEST\",\"name\":\"Test Ingredient\",\"type\":\"CHEESE\"}";
    mockMvc.perform(post("/api/ingredients")
            .with(user("kitchen").roles("KITCHEN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("TC-11: ADMIN puede administrar y crear ingredientes (Autorizado 2xx)")
  public void postIngredient_adminRole_shouldReturn201() throws Exception {
    String payload = "{\"id\":\"JALA\",\"name\":\"Jalapeno Peppers\",\"type\":\"VEGGIES\"}";
    mockMvc.perform(post("/api/ingredients")
            .with(user("admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().is2xxSuccessful());
  }

  // 3. Creación y consulta de Órdenes

  @Test
  @DisplayName("TC-11: Anónimo intentando crear orden es rechazado con 401 Unauthorized")
  public void postOrder_anonymous_shouldReturn401() throws Exception {
    String payload = "{"
        + "\"deliveryName\":\"Anonymous\","
        + "\"deliveryStreet\":\"Street 1\","
        + "\"deliveryCity\":\"City\","
        + "\"deliveryState\":\"State\","
        + "\"deliveryZip\":\"12345\","
        + "\"ccNumber\":\"4111111111111111\","
        + "\"ccExpiration\":\"12/28\","
        + "\"ccCVV\":\"123\","
        + "\"tacos\":[{\"name\":\"Taco\",\"ingredients\":[{\"id\":\"FLTO\",\"name\":\"Flour Tortilla\",\"type\":\"WRAP\"}]}]"
        + "}";

    mockMvc.perform(post("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("TC-11: Anónimo intentando listar órdenes es rechazado con 401 Unauthorized")
  public void getOrders_anonymous_shouldReturn401() throws Exception {
    mockMvc.perform(get("/api/orders"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("TC-11: USER autenticado puede listar sus órdenes (200 OK)")
  public void getOrders_authenticatedUser_shouldReturn200() throws Exception {
    mockMvc.perform(get("/api/orders")
            .with(user("habuma").roles("USER")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("TC-11: ADMIN autenticado puede auditar y listar todas las órdenes (200 OK)")
  public void getOrders_adminUser_shouldReturn200() throws Exception {
    mockMvc.perform(get("/api/orders")
            .with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk());
  }

  // 4. Cocina (KITCHEN o ADMIN)

  @Test
  @DisplayName("TC-11: Anónimo intentando acceder a cocina (/orders/receive) es rechazado con 401 Unauthorized")
  public void getKitchen_anonymous_shouldReturn401() throws Exception {
    mockMvc.perform(get("/orders/receive"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("TC-11: USER intentando acceder a cocina (/orders/receive) es rechazado con 403 Forbidden")
  public void getKitchen_userRole_shouldReturn403() throws Exception {
    mockMvc.perform(get("/orders/receive")
            .with(user("habuma").roles("USER")))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("TC-11: KITCHEN puede acceder a endpoints de cocina (Pasa autorización de seguridad)")
  public void getKitchen_kitchenRole_shouldBeAuthorized() throws Exception {
    mockMvc.perform(get("/orders/receive")
            .with(user("kitchen").roles("KITCHEN")))
        .andExpect(status().isNotFound());
  }

  // 5. Deny-by-default (Rutas no listadas)

  @Test
  @DisplayName("TC-11 Deny-by-default: Ruta no registrada es rechazada con 401 (anónimo)")
  public void unlistedRoute_anonymous_shouldReturn401() throws Exception {
    mockMvc.perform(get("/api/unknown-feature"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("TC-11 Deny-by-default: Ruta no registrada es rechazada con 403 (autenticado)")
  public void unlistedRoute_authenticated_shouldReturn403() throws Exception {
    mockMvc.perform(get("/api/unknown-feature")
            .with(user("habuma").roles("USER")))
        .andExpect(status().isForbidden());
  }

  // 6. Actuator y Spring Data REST

  @Test
  @DisplayName("TC-11 Actuator: /actuator base es público para descubrimiento de Spring Boot Admin (200 OK)")
  public void actuatorBase_anonymous_shouldReturn200() throws Exception {
    mockMvc.perform(get("/actuator"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("TC-11 Actuator: /actuator/health es público (200 OK)")
  public void actuatorHealth_anonymous_shouldReturn200() throws Exception {
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("TC-11 Actuator: /actuator/metrics es rechazado para anónimo (401 Unauthorized)")
  public void actuatorMetrics_anonymous_shouldReturn401() throws Exception {
    mockMvc.perform(get("/actuator/metrics"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("TC-11 Actuator: /actuator/metrics es rechazado para USER (403 Forbidden)")
  public void actuatorMetrics_userRole_shouldReturn403() throws Exception {
    mockMvc.perform(get("/actuator/metrics")
            .with(user("habuma").roles("USER")))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("TC-11 Actuator: /actuator/metrics es accesible para ADMIN (200 OK)")
  public void actuatorMetrics_adminRole_shouldReturn200() throws Exception {
    mockMvc.perform(get("/actuator/metrics")
            .with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("TC-11 Data REST: /data-api es rechazado para USER (403 Forbidden)")
  public void dataRest_userRole_shouldReturn403() throws Exception {
    mockMvc.perform(get("/data-api")
            .with(user("habuma").roles("USER")))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("TC-11 Data REST: /data-api es accesible para ADMIN (200 OK)")
  public void dataRest_adminRole_shouldReturn200() throws Exception {
    mockMvc.perform(get("/data-api")
            .with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk());
  }
}
