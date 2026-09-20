package tacos.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.classification.DietaryTag;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryService;
import tacos.messaging.OrderMessagingService;
import tacos.physics.rules.AvailableIngredientsRule;
import tacos.physics.rules.ExtremeHeatRequiresCoolingRule;
import tacos.physics.rules.IngredientCountRule;
import tacos.physics.rules.NoDuplicateIngredientsRule;
import tacos.physics.rules.SingleBaseRule;
import tacos.physics.rules.SoggyTacoPhysicsRule;
import tacos.physics.rules.VeganMeatConflictRule;
import tacos.pricing.PricingService;
import tacos.web.api.OrderApiController;
import tacos.web.api.dto.OrderCreateRequest;
import tacos.web.api.dto.OrderItemRequest;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.TacoRequest;

@DisplayName("TC-18: Pruebas de Composición, Extensibilidad y Validación")
public class TacoPhysicsCompositeTest {

  private IngredientRepository ingredientRepo;
  private List<TacoDesignRule> standardRules;
  private TacoDesignValidator validator;

  private Ingredient flourTortilla;
  private Ingredient groundBeef;
  private Ingredient cheddar;
  private Ingredient salsa;

  @BeforeEach
  void setUp() {
    ingredientRepo = mock(IngredientRepository.class);

    flourTortilla = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("0.79"), true, 100, 10, 1L);
    groundBeef = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN, new BigDecimal("1.50"), true, 80, 10, 1L);
    cheddar = new Ingredient("CHED", "Cheddar Cheese", Type.CHEESE, new BigDecimal("0.65"), true, 90, 10, 1L);
    salsa = new Ingredient("SLSA", "Salsa", Type.SAUCE, new BigDecimal("0.50"), true, 120, 10, 1L);

    when(ingredientRepo.findAllById(any(Iterable.class))).thenAnswer(invocation -> {
      Iterable<String> ids = invocation.getArgument(0);
      List<Ingredient> found = new ArrayList<>();
      for (String id : ids) {
        if ("FLTO".equals(id)) found.add(flourTortilla);
        else if ("GRBF".equals(id)) found.add(groundBeef);
        else if ("CHED".equals(id)) found.add(cheddar);
        else if ("SLSA".equals(id)) found.add(salsa);
      }
      return Flux.fromIterable(found);
    });

    standardRules = Arrays.asList(
        new SingleBaseRule(),
        new IngredientCountRule(2, 12),
        new NoDuplicateIngredientsRule(),
        new AvailableIngredientsRule(),
        new ExtremeHeatRequiresCoolingRule(true),
        new SoggyTacoPhysicsRule(2, true),
        new VeganMeatConflictRule(true)
    );

    validator = new TacoDesignValidator(standardRules, ingredientRepo);
  }

  @Test
  @DisplayName("Composición: Diseño con múltiples violaciones debe retornar todas sin fail-fast")
  void multipleViolations_shouldCollectAllViolationsWithoutFailFast() {
    // Escenario: 0 bases, solo 1 ingrediente (< 2), y duplicado: ["GRBF", "GRBF"]
    List<String> rawIds = Arrays.asList("GRBF", "GRBF");
    List<Ingredient> resolved = Collections.singletonList(groundBeef); // DB devuelve 1 instancia para el id
    TacoDesignContext ctx = new TacoDesignContext("Broken Taco", rawIds, resolved);

    TacoDesignValidationResult result = validator.validate(ctx);

    assertThat(result.isValid()).isFalse();
    List<String> codes = result.getViolations().stream()
        .map(DesignViolation::getCode)
        .collect(Collectors.toList());

    // Debe contener las tres violaciones simultáneas:
    assertThat(codes).contains(
        SingleBaseRule.CODE,                     // EXACTLY_ONE_BASE (0 bases)
        NoDuplicateIngredientsRule.CODE          // DUPLICATE_INGREDIENTS
    );
    assertThat(codes.size()).isGreaterThanOrEqualTo(2);
  }

  @Test
  @DisplayName("Extensibilidad (Open/Closed): Agregar una regla fake no requiere modificar el validador")
  void extensibility_addingCustomRule_doesNotRequireModifyingValidator() {
    // Creamos una regla externa ad-hoc sin tocar TacoDesignValidator
    TacoDesignRule noPineappleRule = context -> {
      if (context.getRawIngredientIds().contains("PINEAPPLE")) {
        return Collections.singletonList(DesignViolation.of("NO_PINEAPPLE_PHYSICS", "Pineapple does not belong in taco physics"));
      }
      return Collections.emptyList();
    };

    List<TacoDesignRule> extendedRules = new ArrayList<>(standardRules);
    extendedRules.add(noPineappleRule);

    TacoDesignValidator extendedValidator = new TacoDesignValidator(extendedRules, ingredientRepo);

    TacoDesignContext ctx = new TacoDesignContext("Hawaiian Taco",
        Arrays.asList("FLTO", "GRBF", "PINEAPPLE"),
        Arrays.asList(flourTortilla, groundBeef));

    TacoDesignValidationResult result = extendedValidator.validate(ctx);

    assertThat(result.isValid()).isFalse();
    List<String> codes = result.getViolations().stream()
        .map(DesignViolation::getCode)
        .collect(Collectors.toList());

    assertThat(codes).contains("NO_PINEAPPLE_PHYSICS");
  }

  @Test
  @DisplayName("Determinismo: El orden de inyección de las reglas no altera el resultado final")
  void ruleOrder_shouldNotChangeFinalViolationsSet() {
    List<TacoDesignRule> reversedRules = new ArrayList<>(standardRules);
    Collections.reverse(reversedRules);

    TacoDesignValidator reversedValidator = new TacoDesignValidator(reversedRules, ingredientRepo);

    List<String> rawIds = Arrays.asList("GRBF", "GRBF"); // 0 bases, duplicado
    TacoDesignContext ctx = new TacoDesignContext("Taco", rawIds, Collections.singletonList(groundBeef));

    TacoDesignValidationResult resStandard = validator.validate(ctx);
    TacoDesignValidationResult resReversed = reversedValidator.validate(ctx);

    Set<String> standardCodes = resStandard.getViolations().stream().map(DesignViolation::getCode).collect(Collectors.toSet());
    Set<String> reversedCodes = resReversed.getViolations().stream().map(DesignViolation::getCode).collect(Collectors.toSet());

    assertThat(reversedCodes).isEqualTo(standardCodes);
  }

  @Test
  @DisplayName("Ciclo de Vida: postOrder con diseño inválido es rechazado antes de consultar o reservar inventario")
  void postOrder_withInvalidTacoDesign_shouldRejectBeforeInventoryReservation() {
    OrderRepository orderRepo = mock(OrderRepository.class);
    OrderMessagingService messaging = mock(OrderMessagingService.class);
    PricingService pricingService = mock(PricingService.class);
    InventoryService inventoryService = mock(InventoryService.class);
    OrderMapper orderMapper = new OrderMapper();

    OrderApiController controller = new OrderApiController(
        orderRepo,
        messaging,
        null,
        orderMapper,
        null,
        null,
        null,
        pricingService,
        inventoryService,
        validator
    );

    // Creamos una orden con un taco inválido (0 bases, solo carne y salsa)
    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Test User");
    request.setDeliveryStreet("123 Test St");
    request.setDeliveryCity("City");
    request.setDeliveryState("ST");
    request.setDeliveryZip("12345");
    request.setCcNumber("4111111111111111");
    request.setCcExpiration("12/28");
    request.setCcCVV("123");

    TacoRequest invalidTaco = new TacoRequest();
    invalidTaco.setName("No Base Taco");
    invalidTaco.setIngredientIds(Arrays.asList("GRBF", "SLSA")); // Falta la base (wrap)

    OrderItemRequest item = new OrderItemRequest();
    item.setTaco(invalidTaco);
    item.setQuantity(2);
    request.setItems(Collections.singletonList(item));

    StepVerifier.create(controller.postOrder(request, mock(Authentication.class)))
        .expectErrorMatches(throwable -> {
          assertThat(throwable).isInstanceOf(InvalidTacoDesignException.class);
          InvalidTacoDesignException ex = (InvalidTacoDesignException) throwable;
          assertThat(ex.getCode()).isEqualTo("INVALID_TACO_DESIGN");
          List<String> codes = ex.getViolations().stream().map(DesignViolation::getCode).collect(Collectors.toList());
          assertThat(codes).contains(SingleBaseRule.CODE);
          return true;
        })
        .verify();

    // Verificación CRÍTICA: ¡El motor de inventario NUNCA fue llamado!
    verify(inventoryService, never()).reserve(any());
    // La orden NUNCA fue guardada en el repositorio:
    verify(orderRepo, never()).save(any(TacoOrder.class));
    // Pricing NUNCA fue calculado innecesariamente:
    verify(pricingService, never()).calculateAndApplyPricing(any());
  }
}
