package tacos.recommendation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.classification.TacoClassification;
import tacos.classification.TacoClassificationService;
import tacos.data.IngredientRepository;
import tacos.data.TacoRepository;
import tacos.physics.TacoDesignValidator;
import tacos.web.api.dto.IngredientMapper;
import tacos.web.api.dto.TacoOfTheDayResponse;
import tacos.web.api.dto.TacoResponse;

@Service
public class TacoOfTheDayService {

  private final TacoRepository tacoRepo;
  private final IngredientRepository ingredientRepo;
  private final TacoDesignValidator designValidator;
  private final TacoClassificationService classificationService;
  private final IngredientMapper ingredientMapper;
  private final Clock clock;
  private final ZoneId zoneId;

  private final AtomicReference<CachedDailyTaco> cache = new AtomicReference<>();

  @Autowired
  public TacoOfTheDayService(TacoRepository tacoRepo,
                             IngredientRepository ingredientRepo,
                             TacoDesignValidator designValidator,
                             TacoClassificationService classificationService,
                             IngredientMapper ingredientMapper,
                             Clock clock,
                             @Value("${taco.recommendation.zone-id:America/Mexico_City}") String zoneIdStr) {
    this.tacoRepo = tacoRepo;
    this.ingredientRepo = ingredientRepo;
    this.designValidator = designValidator;
    this.classificationService = classificationService != null ? classificationService : new TacoClassificationService(ingredientRepo, tacoRepo);
    this.ingredientMapper = ingredientMapper != null ? ingredientMapper : new IngredientMapper();
    this.clock = clock != null ? clock : Clock.systemDefaultZone();

    ZoneId resolvedZone;
    try {
      resolvedZone = (zoneIdStr != null && !zoneIdStr.trim().isEmpty())
          ? ZoneId.of(zoneIdStr)
          : this.clock.getZone();
    } catch (Exception e) {
      resolvedZone = this.clock.getZone();
    }
    this.zoneId = resolvedZone;
  }

  public TacoOfTheDayService(TacoRepository tacoRepo,
                             IngredientRepository ingredientRepo,
                             TacoDesignValidator designValidator,
                             Clock clock,
                             ZoneId zoneId) {
    this(tacoRepo, ingredientRepo, designValidator, null, new IngredientMapper(), clock, zoneId != null ? zoneId.getId() : null);
  }

  public Mono<TacoOfTheDayResponse> getTacoOfTheDay() {
    LocalDate today = LocalDate.now(clock.withZone(zoneId));

    CachedDailyTaco cached = cache.get();
    if (cached != null && cached.date.equals(today)) {
      return isTacoStillAvailable(cached.taco)
          .flatMap(isAvailable -> {
            if (Boolean.TRUE.equals(isAvailable)) {
              return Mono.just(cached.response);
            }
            // If the cached taco's ingredients are no longer available, invalidate cache and recompute
            cache.set(null);
            return computeTacoOfTheDay(today);
          });
    }

    return computeTacoOfTheDay(today);
  }

  public void invalidateCache() {
    cache.set(null);
  }

  public Clock getClock() {
    return this.clock;
  }

  public ZoneId getZoneId() {
    return this.zoneId;
  }

  private Mono<TacoOfTheDayResponse> computeTacoOfTheDay(LocalDate today) {
    if (tacoRepo == null) {
      return Mono.empty();
    }

    return tacoRepo.findAll()
        .filterWhen(this::isCandidateValidAndAvailable)
        .collectList()
        .flatMap(candidates -> {
          if (candidates.isEmpty()) {
            cache.set(null);
            return Mono.empty();
          }

          // Stable canonical sort by identifier
          candidates.sort(Comparator.comparing(
              Taco::getId,
              Comparator.nullsLast(String::compareTo)
          ));

          // Deterministic cyclic index based on epoch day
          long epochDay = today.toEpochDay();
          int index = (int) Math.floorMod(epochDay, candidates.size());
          Taco selectedTaco = candidates.get(index);

          String reason = generateReason(today, selectedTaco);
          TacoResponse tacoResponse = toTacoResponse(selectedTaco);

          TacoOfTheDayResponse response = TacoOfTheDayResponse.builder()
              .taco(tacoResponse)
              .date(today)
              .reason(reason)
              .build();

          cache.set(new CachedDailyTaco(today, selectedTaco, response));
          return Mono.just(response);
        });
  }

  public Mono<Boolean> isCandidateValidAndAvailable(Taco taco) {
    if (taco == null || taco.getIngredients() == null || taco.getIngredients().isEmpty()) {
      return Mono.just(false);
    }

    Mono<Boolean> availabilityMono = isTacoStillAvailable(taco);

    if (designValidator != null) {
      return availabilityMono.flatMap(available -> {
        if (!available) {
          return Mono.just(false);
        }
        return designValidator.validateTaco(taco)
            .map(res -> res.isValid());
      });
    }

    return availabilityMono;
  }

  public Mono<Boolean> isTacoStillAvailable(Taco taco) {
    if (taco == null || taco.getIngredients() == null || taco.getIngredients().isEmpty()) {
      return Mono.just(false);
    }

    List<String> ids = taco.getIngredients().stream()
        .filter(Objects::nonNull)
        .map(Ingredient::getId)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());

    if (ids.isEmpty()) {
      return Mono.just(false);
    }

    if (ingredientRepo == null) {
      boolean allOk = taco.getIngredients().stream().allMatch(Ingredient::isAvailable);
      return Mono.just(allOk);
    }

    return ingredientRepo.findAllById(ids)
        .collectList()
        .map(resolvedIngredients -> {
          if (resolvedIngredients.size() < ids.size()) {
            return false;
          }
          return resolvedIngredients.stream().allMatch(Ingredient::isAvailable);
        });
  }

  private String generateReason(LocalDate date, Taco taco) {
    String dayName = date.getDayOfWeek().getDisplayName(TextStyle.FULL, new Locale("es", "MX"));
    String capitalizedDay = dayName.substring(0, 1).toUpperCase() + dayName.substring(1);
    return String.format(
        "Recomendación del día porque hoy es %s (%s): '%s' cuenta con ingredientes 100%% frescos y disponibles.",
        capitalizedDay, date, taco.getName() != null ? taco.getName() : "Taco Especial"
    );
  }

  public TacoResponse toTacoResponse(Taco taco) {
    if (taco == null) {
      return null;
    }
    TacoResponse resp = new TacoResponse();
    resp.setId(taco.getId());
    resp.setName(taco.getName());
    resp.setCreatedAt(taco.getCreatedAt());
    if (taco.getIngredients() != null) {
      resp.setIngredients(taco.getIngredients().stream()
          .map(ingredientMapper::toResponse)
          .collect(Collectors.toList()));
    }
    if (taco.getDietaryTags() != null && !taco.getDietaryTags().isEmpty()) {
      resp.setDietaryTags(taco.getDietaryTags());
      resp.setAllergens(taco.getAllergens());
      resp.setSpiceLevel(taco.getSpiceLevel());
    } else if (classificationService != null && taco.getIngredients() != null && !taco.getIngredients().isEmpty()) {
      TacoClassification tc = classificationService.classify(taco.getIngredients());
      resp.setDietaryTags(tc.getDietaryTags());
      resp.setAllergens(tc.getAllergens());
      resp.setSpiceLevel(tc.getSpiceLevel());
    }
    resp.setDisclaimer(TacoClassification.ACADEMIC_DISCLAIMER);
    return resp;
  }

  private static class CachedDailyTaco {
    final LocalDate date;
    final Taco taco;
    final TacoOfTheDayResponse response;

    CachedDailyTaco(LocalDate date, Taco taco, TacoOfTheDayResponse response) {
      this.date = date;
      this.taco = taco;
      this.response = response;
    }
  }

}
