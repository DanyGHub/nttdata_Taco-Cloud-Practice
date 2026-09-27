package tacos.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;
import tacos.idempotency.IdempotencyRecordRepository;
import tacos.outbox.OutboxEventRepository;

/**
 * Prueba de regresión del contrato reactivo de repositorios.
 */
public class ReactiveRepositoryContractRegressionTest {

  private static final List<Class<?>> REACTIVE_REPOSITORIES = Arrays.asList(
      OrderRepository.class,
      TacoRepository.class,
      IngredientRepository.class,
      PaymentMethodRepository.class,
      UserRepository.class,
      OutboxEventRepository.class,
      IdempotencyRecordRepository.class
  );

  @Test
  @DisplayName("TC-04/TC-36: Todos los repositorios deben extender ReactiveCrudRepository y nunca devolver void")
  public void allRepositoriesMustBeReactiveAndNeverReturnVoid() {
    for (Class<?> repoInterface : REACTIVE_REPOSITORIES) {
      // 1. Debe extender ReactiveCrudRepository
      assertThat(ReactiveCrudRepository.class.isAssignableFrom(repoInterface))
          .as("El repositorio %s debe extender ReactiveCrudRepository", repoInterface.getSimpleName())
          .isTrue();

      // 2. Verificar que los métodos de mutación devuelven Publisher (Mono/Flux) y NUNCA void
      assertNoVoidMutations(repoInterface);
    }
  }

  @Test
  @DisplayName("TC-36 Regresión Negativa: Reintroducir void save o void delete en un repositorio produce fallo explícito")
  public void regressionDetector_failsIfVoidMethodIsReintroduced() {
    // Interfaz que simula una regresión donde alguien reintroduce 'void save'
    assertThatThrownBy(() -> assertNoVoidMutations(LegacyBlockingRepositoryWithVoidSave.class))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Regression detected: repository method 'save' in LegacyBlockingRepositoryWithVoidSave returns void");

    // Interfaz que simula una regresión donde alguien reintroduce 'void delete'
    assertThatThrownBy(() -> assertNoVoidMutations(LegacyBlockingRepositoryWithVoidDelete.class))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Regression detected: repository method 'delete' in LegacyBlockingRepositoryWithVoidDelete returns void");
  }

  private void assertNoVoidMutations(Class<?> repoClass) {
    for (Method method : repoClass.getMethods()) {
      String methodName = method.getName();
      if (isMutationMethod(methodName)) {
        Class<?> returnType = method.getReturnType();

        if (returnType.equals(Void.TYPE)) {
          throw new AssertionError(String.format(
              "Regression detected: repository method '%s' in %s returns void instead of reactive Publisher (Mono/Flux). TC-04 contract violated!",
              methodName, repoClass.getSimpleName()));
        }

        if (!Publisher.class.isAssignableFrom(returnType)) {
          throw new AssertionError(String.format(
              "Regression detected: repository method '%s' in %s returns non-reactive type '%s' instead of Publisher (Mono/Flux)",
              methodName, repoClass.getSimpleName(), returnType.getSimpleName()));
        }
      }
    }
  }

  private boolean isMutationMethod(String name) {
    return name.equals("save")
        || name.equals("saveAll")
        || name.equals("delete")
        || name.equals("deleteById")
        || name.equals("deleteAll");
  }

  // Interfaces de prueba para verificar que el detector de regresiones atraparía cualquier error
  interface LegacyBlockingRepositoryWithVoidSave {
    void save(Object entity);
  }

  interface LegacyBlockingRepositoryWithVoidDelete {
    void delete(Object entity);
  }

}
