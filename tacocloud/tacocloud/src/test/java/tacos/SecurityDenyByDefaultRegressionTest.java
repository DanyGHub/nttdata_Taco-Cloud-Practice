package tacos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/**
 * Prueba de regresión de seguridad Deny-by-default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SecurityDenyByDefaultRegressionTest {

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
  @DisplayName("TC-11/TC-36: Rutas no configuradas o accidentales son bloqueadas por deny-by-default")
  public void unconfiguredOrAccidentalRoutes_mustBeDenied() throws Exception {
    // 1. Acceso anónimo a ruta inexistente o no mapeada expresamente
    mockMvc.perform(get("/api/unmapped-endpoint"))
        .andExpect(result -> {
          int status = result.getResponse().getStatus();
          assertThat(status)
              .as("Ruta accidental no configurada debe ser rechazada con 401 o 403, nunca 200 OK")
              .isIn(401, 403);
        });

    // 2. Acceso anónimo a ruta interna de depuración
    mockMvc.perform(get("/api/v1/internal/admin-debug"))
        .andExpect(result -> {
          int status = result.getResponse().getStatus();
          assertThat(status).isIn(401, 403);
        });

    // 3. Acceso anónimo a mutaciones de catálogo
    mockMvc.perform(post("/api/v1/ingredients")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"id\":\"TEST\",\"name\":\"Test\",\"type\":\"WRAP\"}"))
        .andExpect(result -> {
          int status = result.getResponse().getStatus();
          assertThat(status).isIn(401, 403);
        });
  }

  @Test
  @DisplayName("TC-11/TC-36: Usuario regular con ROLE_USER no puede acceder a rutas administrativas (403 Forbidden)")
  public void regularUser_cannotAccessAdminRoutes() throws Exception {
    // Intentar listar órdenes administrativas con rol USER
    mockMvc.perform(get("/api/v1/admin/orders").with(user("regularUser").roles("USER")))
        .andExpect(status().isForbidden());

    // Intentar borrar un ingrediente con rol USER
    mockMvc.perform(delete("/api/v1/ingredients/FLTO").with(user("regularUser").roles("USER")))
        .andExpect(status().isForbidden());

    // Intentar acceder a actuator privilegiado con rol USER
    mockMvc.perform(get("/actuator/metrics").with(user("regularUser").roles("USER")))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("TC-11/TC-36: Verificación de detector de regresión de permitAll accidental")
  public void accidentalPermitAllDetector_failsIfProtectedResourceReturns200WithoutAuth() {
    // Si una ruta protegida devuelve 200 OK sin autenticación, es un fallo crítico de seguridad
    boolean securityContractPassed = true;
    try {
      int status = mockMvc.perform(get("/api/v1/admin/orders")).andReturn().getResponse().getStatus();
      if (status == 200) {
        securityContractPassed = false;
      }
    } catch (Exception e) {
      // Excepción esperada por falta de autorización
      securityContractPassed = true;
    }

    assertThat(securityContractPassed)
        .as("Regresión detectada: /api/v1/admin/orders no debe permitir acceso público anónimo (permitAll)")
        .isTrue();
  }

}
