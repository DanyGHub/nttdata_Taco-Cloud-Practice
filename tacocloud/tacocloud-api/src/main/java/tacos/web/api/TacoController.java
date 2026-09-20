package tacos.web.api;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.classification.Allergen;
import tacos.classification.DietaryTag;
import tacos.classification.SpiceLevel;
import tacos.classification.TacoClassification;
import tacos.classification.TacoClassificationService;
import tacos.data.IngredientRepository;
import tacos.data.TacoEntityCallback;
import tacos.data.TacoRepository;
import tacos.physics.TacoDesignValidator;
import tacos.recommendation.TacoOfTheDayService;
import tacos.search.TacoPage;
import tacos.search.TacoSearchCriteria;
import tacos.web.api.dto.IngredientMapper;
import tacos.web.api.dto.TacoClassificationResponse;
import tacos.web.api.dto.TacoDesignRequest;
import tacos.web.api.dto.TacoDesignValidationResponse;
import tacos.web.api.dto.TacoOfTheDayResponse;
import tacos.web.api.dto.TacoResponse;

@RestController
@RequestMapping(path = "/api/tacos", produces = "application/json")
@CrossOrigin(origins="http://localhost:8080")
public class TacoController {

  private final TacoRepository tacoRepo;
  private final TacoClassificationService classificationService;
  private final IngredientRepository ingredientRepo;
  private final TacoDesignValidator designValidator;
  private final IngredientMapper ingredientMapper;
  private final TacoOfTheDayService tacoOfTheDayService;
  private final int maxPageSize;

  @Autowired
  public TacoController(TacoRepository tacoRepo,
                        TacoClassificationService classificationService,
                        IngredientRepository ingredientRepo,
                        TacoDesignValidator designValidator,
                        IngredientMapper ingredientMapper,
                        TacoOfTheDayService tacoOfTheDayService,
                        @Value("${taco.search.max-page-size:50}") int maxPageSize) {
    this.tacoRepo = tacoRepo;
    this.classificationService = classificationService != null ? classificationService : new TacoClassificationService(ingredientRepo, tacoRepo);
    this.ingredientRepo = ingredientRepo;
    this.designValidator = designValidator;
    this.ingredientMapper = ingredientMapper != null ? ingredientMapper : new IngredientMapper();
    this.tacoOfTheDayService = tacoOfTheDayService != null ? tacoOfTheDayService : new TacoOfTheDayService(tacoRepo, ingredientRepo, designValidator, this.classificationService, this.ingredientMapper, Clock.systemDefaultZone(), "America/Mexico_City");
    this.maxPageSize = maxPageSize > 0 ? maxPageSize : 50;
  }

  public TacoController(TacoRepository tacoRepo,
                        TacoClassificationService classificationService,
                        IngredientRepository ingredientRepo,
                        TacoDesignValidator designValidator) {
    this(tacoRepo, classificationService, ingredientRepo, designValidator, new IngredientMapper(), null, 50);
  }

  public TacoController(TacoRepository tacoRepo,
                        TacoClassificationService classificationService,
                        IngredientRepository ingredientRepo) {
    this(tacoRepo, classificationService, ingredientRepo, null, new IngredientMapper(), null, 50);
  }

  public TacoController(TacoRepository tacoRepo, TacoOfTheDayService tacoOfTheDayService) {
    this(tacoRepo, new TacoClassificationService(null, tacoRepo), null, null, new IngredientMapper(), tacoOfTheDayService, 50);
  }

  public TacoController(TacoRepository tacoRepo) {
    this(tacoRepo, new TacoClassificationService(null, tacoRepo), null, null, new IngredientMapper(), null, 50);
  }

  @GetMapping
  public Mono<TacoPage<TacoResponse>> searchTacos(
      @RequestParam(name = "name", required = false) String name,
      @RequestParam(name = "ingredientId", required = false) String ingredientId,
      @RequestParam(name = "diet", required = false) DietaryTag diet,
      @RequestParam(name = "excludeAllergen", required = false) Allergen excludeAllergen,
      @RequestParam(name = "spice", required = false) SpiceLevel spice,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size,
      @RequestParam(name = "sort", defaultValue = "createdAt,desc") String sort) {

    if (page < 0) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page index must not be less than zero"));
    }
    if (size < 1) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size must be greater than zero"));
    }
    if (size > maxPageSize) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
          String.format("Page size (%d) exceeds maximum allowed size (%d)", size, maxPageSize)));
    }

    TacoSearchCriteria criteria = TacoSearchCriteria.builder()
        .name(name)
        .ingredientId(ingredientId)
        .diet(diet)
        .excludeAllergen(excludeAllergen)
        .spice(spice)
        .page(page)
        .size(size)
        .sort(sort)
        .build();

    try {
      return tacoRepo.searchTacos(criteria)
          .map(tacoPage -> tacoPage.map(this::toTacoResponse))
          .onErrorMap(IllegalArgumentException.class,
              ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()));
    } catch (IllegalArgumentException ex) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }
  }

  @GetMapping(params="recent")
  public Flux<Taco> recentTacos() {
    return tacoRepo.findAll().take(12);
  }

  @PostMapping(consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<Taco> postTaco(@RequestBody Taco taco) {
    if (taco == null) {
      return Mono.empty();
    }

    Mono<Void> validationMono = (designValidator != null)
        ? designValidator.validateTacoAndThrow(taco)
        : Mono.empty();

    return validationMono.then(Mono.defer(() -> {
      if (ingredientRepo != null && taco.getIngredients() != null && !taco.getIngredients().isEmpty()) {
        List<String> ingredientIds = taco.getIngredients().stream()
            .filter(Objects::nonNull)
            .map(Ingredient::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        if (!ingredientIds.isEmpty()) {
          return ingredientRepo.findAllById(ingredientIds)
              .collectList()
              .flatMap(officialIngredients -> {
                taco.setIngredients(officialIngredients);
                TacoEntityCallback.syncClassification(taco);
                return tacoRepo.save(taco);
              });
        }
      }

      return tacoRepo.save(taco);
    }));
  }

  @PostMapping(path = "/validate", consumes = "application/json")
  public Mono<TacoDesignValidationResponse> validateTacoDesign(@RequestBody TacoDesignRequest request) {
    if (request == null) {
      return Mono.just(TacoDesignValidationResponse.of(false, Collections.emptyList()));
    }
    List<String> rawIds = request.extractIngredientIds();
    if (designValidator != null) {
      return designValidator.validateDesign(request.getName(), rawIds)
          .map(res -> TacoDesignValidationResponse.of(res.isValid(), res.getViolations()));
    }
    return Mono.just(TacoDesignValidationResponse.of(true, Collections.emptyList()));
  }

  @GetMapping("/today")
  public Mono<ResponseEntity<TacoOfTheDayResponse>> getTacoOfTheDay() {
    if (tacoOfTheDayService == null) {
      return Mono.just(ResponseEntity.notFound().build());
    }
    return tacoOfTheDayService.getTacoOfTheDay()
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  @GetMapping("/{id}")
  public Mono<Taco> tacoById(@PathVariable("id") String id) {
    return tacoRepo.findById(id);
  }

  @GetMapping("/{id}/classification")
  public Mono<ResponseEntity<TacoClassificationResponse>> getTacoClassification(@PathVariable("id") String id) {
    return tacoRepo.findById(id)
        .flatMap(taco -> classificationService.classifyTaco(taco)
            .map(classification -> ResponseEntity.ok(
                TacoClassificationResponse.from(taco.getId(), taco.getName(), classification)
            ))
        )
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  private TacoResponse toTacoResponse(Taco taco) {
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

}
