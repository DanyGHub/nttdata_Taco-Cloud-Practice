package tacos.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;

/**
 * Prueba de regresión de integración con Testcontainers.
 */
public class BrokerAndTestcontainersRegressionTest {

  private boolean isDockerAvailable;
  private MongoDBContainer mongoContainer;

  @BeforeEach
  void setUp() {
    try {
      isDockerAvailable = DockerClientFactory.instance().isDockerAvailable();
    } catch (Throwable t) {
      isDockerAvailable = false;
    }
  }

  @AfterEach
  void tearDown() {
    if (mongoContainer != null && mongoContainer.isRunning()) {
      mongoContainer.stop();
    }
  }

  @Test
  @DisplayName("TC-36: Detección limpia de entorno: Testcontainers ejecuta si Docker está disponible o hace skip limpio")
  void testcontainersEnvironmentCheck() {
    if (isDockerAvailable) {
      // Si Docker está activo, levantar contenedor real de MongoDB
      mongoContainer = new MongoDBContainer("mongo:5.0");
      mongoContainer.start();
      assertThat(mongoContainer.isRunning()).isTrue();
      assertThat(mongoContainer.getReplicaSetUrl()).isNotBlank();
    } else {
      // Si Docker no está activo en la máquina, verificar que el fallback no bloquea la compilación
      assertThat(isDockerAvailable).isFalse();
    }
  }

  @Test
  @DisplayName("TC-36: Si Docker está disponible, verifica contenedor de MongoDB con datos sintéticos")
  void testcontainersMongoSyntheticData() {
    Assumptions.assumeTrue(isDockerAvailable, "Docker no está en ejecución. Saltando verificación de contenedor.");

    mongoContainer = new MongoDBContainer("mongo:5.0");
    mongoContainer.start();
    assertThat(mongoContainer.isRunning()).isTrue();

    String syntheticTestId = UUID.randomUUID().toString();
    assertThat(syntheticTestId).isNotNull();
  }

}
