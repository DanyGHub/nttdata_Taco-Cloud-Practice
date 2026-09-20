package tacos.physics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.data.IngredientRepository;

@Service
public class TacoDesignValidator {

  private final List<TacoDesignRule> rules;
  private final IngredientRepository ingredientRepo;

  @Autowired
  public TacoDesignValidator(List<TacoDesignRule> rules, IngredientRepository ingredientRepo) {
    this.rules = rules != null ? Collections.unmodifiableList(rules) : Collections.emptyList();
    this.ingredientRepo = ingredientRepo;
  }

  public TacoDesignValidator(List<TacoDesignRule> rules) {
    this(rules, null);
  }

  /**
   * Ejecuta todas las reglas inyectadas sobre el contexto proporcionado,
   * acumulando la totalidad de violaciones sin fail-fast determinista.
   */
  public TacoDesignValidationResult validate(TacoDesignContext context) {
    if (context == null) {
      context = new TacoDesignContext("", Collections.emptyList(), Collections.emptyList());
    }

    List<DesignViolation> allViolations = new ArrayList<>();
    for (TacoDesignRule rule : rules) {
      List<DesignViolation> violations = rule.validate(context);
      if (violations != null && !violations.isEmpty()) {
        allViolations.addAll(violations);
      }
    }

    return new TacoDesignValidationResult(allViolations.isEmpty(), allViolations);
  }

  /**
   * Recupera los ingredientes canónicos desde el catálogo oficial y ejecuta las reglas.
   */
  public Mono<TacoDesignValidationResult> validateDesign(String name, List<String> rawIngredientIds) {
    List<String> nonNullRawIds = rawIngredientIds != null ? rawIngredientIds.stream()
        .filter(Objects::nonNull)
        .collect(Collectors.toList()) : Collections.emptyList();

    if (ingredientRepo == null) {
      TacoDesignContext context = new TacoDesignContext(name, nonNullRawIds, Collections.emptyList());
      return Mono.just(validate(context));
    }

    if (nonNullRawIds.isEmpty()) {
      TacoDesignContext context = new TacoDesignContext(name, nonNullRawIds, Collections.emptyList());
      return Mono.just(validate(context));
    }

    return ingredientRepo.findAllById(nonNullRawIds)
        .collectList()
        .map(officialIngredients -> {
          TacoDesignContext context = new TacoDesignContext(name, nonNullRawIds, officialIngredients);
          return validate(context);
        });
  }

  /**
   * Valida el diseño y arroja InvalidTacoDesignException si existen violaciones.
   */
  public Mono<Void> validateAndThrow(String name, List<String> rawIngredientIds) {
    return validateDesign(name, rawIngredientIds)
        .flatMap(result -> {
          if (!result.isValid()) {
            return Mono.error(new InvalidTacoDesignException(result.getViolations()));
          }
          return Mono.empty();
        });
  }

  /**
   * Valida un Taco de dominio antes de permitir su persistencia.
   */
  public Mono<Void> validateTacoAndThrow(Taco taco) {
    if (taco == null) {
      return Mono.error(new InvalidTacoDesignException(
          Collections.singletonList(DesignViolation.of("NULL_TACO", "Taco cannot be null", "taco"))
      ));
    }

    List<String> rawIngredientIds = Collections.emptyList();
    if (taco.getIngredients() != null) {
      rawIngredientIds = taco.getIngredients().stream()
          .filter(Objects::nonNull)
          .map(Ingredient::getId)
          .filter(Objects::nonNull)
          .collect(Collectors.toList());
    }

    return validateAndThrow(taco.getName(), rawIngredientIds);
  }

  public List<TacoDesignRule> getRules() {
    return rules;
  }
}
