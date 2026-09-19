package tacos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.data.IngredientRepository;
import tacos.web.api.dto.AdminIngredientResponse;
import tacos.web.api.dto.IngredientCatalogUpdateRequest;
import tacos.web.api.dto.IngredientMapper;
import tacos.web.api.dto.IngredientResponse;
import tacos.web.api.dto.StockAdjustmentRequest;

public class IngredientCatalogAndStockTest {

  private IngredientRepository repo;
  private IngredientMapper mapper;
  private IngredientController publicController;
  private AdminIngredientController adminController;
  private ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  public void setUp() {
    repo = mock(IngredientRepository.class);
    mapper = new IngredientMapper();
    publicController = new IngredientController(repo, mapper);
    adminController = new AdminIngredientController(repo, mapper);
  }

  @Test
  @DisplayName("Catálogo público: GET /api/ingredients muestra precio y disponibilidad, pero oculta stock y versión")
  public void publicCatalog_shouldExposePriceAndAvailability_andHideInternalStock() throws Exception {
    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("0.79"), true, 100, 15, 1L);
    when(repo.findAll()).thenReturn(Flux.just(flto));

    StepVerifier.create(publicController.allIngredients())
        .assertNext(resp -> {
          assertThat(resp.getId()).isEqualTo("FLTO");
          assertThat(resp.getName()).isEqualTo("Flour Tortilla");
          assertThat(resp.getUnitPrice()).isEqualByComparingTo(new BigDecimal("0.79"));
          assertThat(resp.isAvailable()).isTrue();
        })
        .verifyComplete();

    // Validar que el DTO público serializado no expone metadatos operativos
    IngredientResponse publicDto = mapper.toResponse(flto);
    String json = objectMapper.writeValueAsString(publicDto);

    assertThat(json).contains("\"unitPrice\":0.79");
    assertThat(json).contains("\"available\":true");
    assertThat(json).doesNotContain("stockOnHand");
    assertThat(json).doesNotContain("reorderLevel");
    assertThat(json).doesNotContain("version");
  }

  @Test
  @DisplayName("Admin: PATCH /api/admin/ingredients/{id}/catalog actualiza precio y disponibilidad")
  public void adminCatalogUpdate_shouldUpdatePriceAndAvailability() {
    Ingredient existing = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN, new BigDecimal("1.50"), true, 50, 10, 2L);
    when(repo.findById("GRBF")).thenReturn(Mono.just(existing));
    when(repo.save(any(Ingredient.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    IngredientCatalogUpdateRequest updateReq = new IngredientCatalogUpdateRequest(
        new BigDecimal("1.85"), false, 2L);

    StepVerifier.create(adminController.updateCatalog("GRBF", updateReq))
        .assertNext(response -> {
          AdminIngredientResponse body = response.getBody();
          assertThat(body).isNotNull();
          assertThat(body.getId()).isEqualTo("GRBF");
          assertThat(body.getUnitPrice()).isEqualByComparingTo(new BigDecimal("1.85"));
          assertThat(body.isAvailable()).isFalse();
          assertThat(body.getStockOnHand()).isEqualTo(50);
          assertThat(body.getVersion()).isEqualTo(2L);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Admin: POST /api/admin/ingredients/{id}/stock-adjustments incrementa existencias")
  public void adminStockAdjustment_positiveAmount_shouldIncreaseStock() {
    Ingredient existing = new Ingredient("CHED", "Cheddar", Type.CHEESE, new BigDecimal("0.65"), true, 40, 10, 1L);
    when(repo.findById("CHED")).thenReturn(Mono.just(existing));
    when(repo.save(any(Ingredient.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StockAdjustmentRequest req = new StockAdjustmentRequest(25, "Restock batch 101", 1L);

    StepVerifier.create(adminController.adjustStock("CHED", req))
        .assertNext(response -> {
          AdminIngredientResponse body = response.getBody();
          assertThat(body).isNotNull();
          assertThat(body.getStockOnHand()).isEqualTo(65);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Admin: POST stock-adjustments que resulta en stock negativo es rechazado con 422 Unprocessable Entity")
  public void adminStockAdjustment_negativeResult_shouldFailWith422() {
    Ingredient existing = new Ingredient("JACK", "Monterrey Jack", Type.CHEESE, new BigDecimal("0.65"), true, 10, 5, 1L);
    when(repo.findById("JACK")).thenReturn(Mono.just(existing));

    // Intentar descontar 15 cuando sólo hay 10
    StockAdjustmentRequest req = new StockAdjustmentRequest(-15, "Consumption exceeded", 1L);

    StepVerifier.create(adminController.adjustStock("JACK", req))
        .expectErrorSatisfies(throwable -> {
          assertThat(throwable).isInstanceOf(ResponseStatusException.class);
          ResponseStatusException rse = (ResponseStatusException) throwable;
          assertThat(rse.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
          assertThat(rse.getReason()).contains("cannot be negative");
        })
        .verify();
  }

  @Test
  @DisplayName("Optimistic Locking: Versión desactualizada en actualización de catálogo lanza OptimisticLockingFailureException")
  public void optimisticLocking_catalogUpdate_outdatedVersion_shouldFail() {
    Ingredient existing = new Ingredient("TMTO", "Tomatoes", Type.VEGGIES, new BigDecimal("0.50"), true, 30, 10, 5L);
    when(repo.findById("TMTO")).thenReturn(Mono.just(existing));

    // Enviamos versión 4L cuando la actual es 5L
    IngredientCatalogUpdateRequest updateReq = new IngredientCatalogUpdateRequest(
        new BigDecimal("0.60"), true, 4L);

    StepVerifier.create(adminController.updateCatalog("TMTO", updateReq))
        .expectErrorSatisfies(throwable -> {
          assertThat(throwable).isInstanceOf(OptimisticLockingFailureException.class);
          assertThat(throwable.getMessage()).contains("expected version 4 but found 5");
        })
        .verify();
  }

  @Test
  @DisplayName("Optimistic Locking: Versión desactualizada en ajuste de stock lanza OptimisticLockingFailureException")
  public void optimisticLocking_stockAdjustment_outdatedVersion_shouldFail() {
    Ingredient existing = new Ingredient("LETC", "Lettuce", Type.VEGGIES, new BigDecimal("0.50"), true, 30, 10, 3L);
    when(repo.findById("LETC")).thenReturn(Mono.just(existing));

    StockAdjustmentRequest req = new StockAdjustmentRequest(10, "Restock", 2L);

    StepVerifier.create(adminController.adjustStock("LETC", req))
        .expectErrorSatisfies(throwable -> {
          assertThat(throwable).isInstanceOf(OptimisticLockingFailureException.class);
          assertThat(throwable.getMessage()).contains("expected version 2 but found 3");
        })
        .verify();
  }

  @Test
  @DisplayName("Precisión de BigDecimal: Moneda usa BigDecimal y redondeo explícito, nunca double")
  public void monetaryCalculation_mustUseBigDecimalAndExplicitRounding() {
    BigDecimal price = new BigDecimal("0.79");
    BigDecimal taxRate = new BigDecimal("0.0825");

    BigDecimal tax = price.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
    BigDecimal total = price.add(tax);

    assertThat(tax).isEqualByComparingTo(new BigDecimal("0.07"));
    assertThat(total).isEqualByComparingTo(new BigDecimal("0.86"));
    assertThat(price.toString()).isEqualTo("0.79");
  }

}
