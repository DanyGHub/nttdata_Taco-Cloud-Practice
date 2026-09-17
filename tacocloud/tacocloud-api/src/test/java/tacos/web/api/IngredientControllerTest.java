package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.data.IngredientRepository;
import tacos.web.api.dto.IngredientRequest;
import tacos.web.api.dto.IngredientResponse;

public class IngredientControllerTest {

  // TC-01: Actualizar un ingrediente sin perder el publisher

  @Test
  public void shouldUpdateIngredientOk() {
    IngredientRepository repo = Mockito.mock(IngredientRepository.class);
    Ingredient original = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
    Ingredient updated = new Ingredient("FLTO", "Flour Tortilla Updated", Type.WRAP);

    when(repo.findById("FLTO")).thenReturn(Mono.just(original));
    when(repo.save(any(Ingredient.class))).thenReturn(Mono.just(updated));

    WebTestClient testClient = WebTestClient.bindToController(new IngredientController(repo)).build();

    testClient.put()
        .uri("/api/ingredients/FLTO")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(updated)
        .exchange()
        .expectStatus().isOk()
        .expectHeader().doesNotExist("Location")
        .expectBody(Ingredient.class)
        .isEqualTo(updated);

    verify(repo, times(1)).save(any(Ingredient.class));
  }

  @Test
  public void shouldUpdateIngredientBadRequestWhenIdMismatched() {
    IngredientRepository repo = Mockito.mock(IngredientRepository.class);
    Ingredient body = new Ingredient("COTO", "Corn Tortilla", Type.WRAP);

    WebTestClient testClient = WebTestClient.bindToController(new IngredientController(repo)).build();

    testClient.put()
        .uri("/api/ingredients/FLTO")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus().isBadRequest();

    verify(repo, never()).save(any());
  }

  @Test
  public void shouldUpdateIngredientBadRequestWhenIdNull() {
    IngredientRepository repo = Mockito.mock(IngredientRepository.class);
    Ingredient body = new Ingredient(null, "Corn Tortilla", Type.WRAP);

    WebTestClient testClient = WebTestClient.bindToController(new IngredientController(repo)).build();

    testClient.put()
        .uri("/api/ingredients/FLTO")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus().isBadRequest();

    verify(repo, never()).save(any());
  }

  @Test
  public void shouldUpdateIngredientNotFound() {
    IngredientRepository repo = Mockito.mock(IngredientRepository.class);
    Ingredient body = new Ingredient("UNKNOWN", "Unknown Ingredient", Type.WRAP);

    when(repo.findById("UNKNOWN")).thenReturn(Mono.empty());

    WebTestClient testClient = WebTestClient.bindToController(new IngredientController(repo)).build();

    testClient.put()
        .uri("/api/ingredients/UNKNOWN")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()
        .expectStatus().isNotFound();

    verify(repo, never()).save(any());
  }

  @Test
  public void stepVerifier_shouldUpdateIngredientAndExecuteSaveOnSubscription() {
    IngredientRepository repo = Mockito.mock(IngredientRepository.class);
    IngredientController controller = new IngredientController(repo);

    Ingredient existing = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
    IngredientRequest updateData = new IngredientRequest("FLTO", "Flour Tortilla Premium", Type.WRAP);
    Ingredient savedIngredient = new Ingredient("FLTO", "Flour Tortilla Premium", Type.WRAP);

    when(repo.findById("FLTO")).thenReturn(Mono.just(existing));
    when(repo.save(any(Ingredient.class))).thenReturn(Mono.just(savedIngredient));

    Mono<ResponseEntity<IngredientResponse>> resultMono = controller.updateIngredient("FLTO", updateData);

    verify(repo, never()).save(any());  // Lazy execution: Before subscribing, save() should not have been called

    StepVerifier.create(resultMono)
        .expectNextMatches(response -> response.getStatusCode() == HttpStatus.OK && response.getBody() != null &&
            "Flour Tortilla Premium".equals(response.getBody().getName()))
        .verifyComplete();

    verify(repo, times(1)).save(any(Ingredient.class));
  }

  @Test
  public void stepVerifier_shouldEmitErrorWhenIngredientNotFoundOnUpdate() {
    IngredientRepository repo = Mockito.mock(IngredientRepository.class);
    IngredientController controller = new IngredientController(repo);
    IngredientRequest updateData = new IngredientRequest("MISSING", "Missing Ingredient", Type.WRAP);

    when(repo.findById("MISSING")).thenReturn(Mono.empty());

    Mono<ResponseEntity<IngredientResponse>> resultMono = controller.updateIngredient("MISSING", updateData);

    StepVerifier.create(resultMono)
        .expectErrorMatches(throwable -> throwable instanceof ResponseStatusException &&
            ((ResponseStatusException) throwable).getStatus() == HttpStatus.NOT_FOUND)
        .verify();

    verify(repo, never()).save(any());
  }

  // TC-02: Eliminar de verdad y responder con semántica HTTP

  @Test
  public void shouldDeleteIngredientNoContent() {
    IngredientRepository repo = Mockito.mock(IngredientRepository.class);
    when(repo.existsById("FLTO")).thenReturn(Mono.just(true));
    when(repo.deleteById("FLTO")).thenReturn(Mono.empty());

    WebTestClient testClient = WebTestClient.bindToController(new IngredientController(repo)).build();

    testClient.delete()
        .uri("/api/ingredients/FLTO")
        .exchange()
        .expectStatus().isNoContent()
        .expectBody().isEmpty();

    verify(repo, times(1)).deleteById("FLTO");
  }

  @Test
  public void shouldDeleteIngredientNotFound() {
    IngredientRepository repo = Mockito.mock(IngredientRepository.class);
    when(repo.existsById("MISSING")).thenReturn(Mono.just(false));

    WebTestClient testClient = WebTestClient.bindToController(new IngredientController(repo)).build();

    testClient.delete()
        .uri("/api/ingredients/MISSING")
        .exchange()
        .expectStatus().isNotFound();

    verify(repo, never()).deleteById(any(String.class));
  }

  // TC-03: Construir Location sin localhost ni rutas rotas

  @Test
  public void shouldPostIngredientCreatedWithDynamicLocation() {
    IngredientRepository repo = Mockito.mock(IngredientRepository.class);
    Ingredient newIngredient = new Ingredient("CHED", "Cheddar", Type.CHEESE);

    when(repo.save(any(Ingredient.class))).thenReturn(Mono.just(newIngredient));

    WebTestClient testClient = WebTestClient.bindToController(new IngredientController(repo)).build();

    testClient.post()
        .uri("/api/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(newIngredient)
        .exchange()
        .expectStatus().isCreated()
        .expectHeader().value("Location", location -> {
          assert location != null && location.endsWith("/api/ingredients/CHED");
        })
        .expectBody(Ingredient.class)
        .isEqualTo(newIngredient);

    verify(repo, times(1)).save(any(Ingredient.class));
  }

  @Test
  public void shouldPostIngredientBadRequestWhenEmptyBody() {
    IngredientRepository repo = Mockito.mock(IngredientRepository.class);

    WebTestClient testClient = WebTestClient.bindToController(new IngredientController(repo)).build();

    testClient.post()
        .uri("/api/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isBadRequest();

    verify(repo, never()).save(any());
  }

}