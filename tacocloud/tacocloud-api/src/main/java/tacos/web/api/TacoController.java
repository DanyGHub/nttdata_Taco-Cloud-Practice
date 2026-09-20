package tacos.web.api;

import java.security.Principal;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
import tacos.User;
import tacos.classification.Allergen;
import tacos.classification.DietaryTag;
import tacos.classification.SpiceLevel;
import tacos.classification.TacoClassification;
import tacos.classification.TacoClassificationService;
import tacos.data.IngredientRepository;
import tacos.data.TacoEntityCallback;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;
import tacos.physics.TacoDesignValidator;
import tacos.rating.TacoRatingService;
import tacos.recommendation.TacoOfTheDayService;
import tacos.search.TacoPage;
import tacos.search.TacoSearchCriteria;
import tacos.web.api.dto.IngredientMapper;
import tacos.web.api.dto.RatingRequest;
import tacos.web.api.dto.TacoClassificationResponse;
import tacos.web.api.dto.TacoDesignRequest;
import tacos.web.api.dto.TacoDesignValidationResponse;
import tacos.web.api.dto.TacoOfTheDayResponse;
import tacos.web.api.dto.TacoRatingSummaryResponse;
import tacos.web.api.dto.TacoResponse;
import tacos.web.api.dto.TopTacoResponse;

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
  private final TacoRatingService tacoRatingService;
  private final UserRepository userRepo;
  private final int maxPageSize;

  @Autowired
  public TacoController(TacoRepository tacoRepo,
                        TacoClassificationService classificationService,
                        IngredientRepository ingredientRepo,
                        TacoDesignValidator designValidator,
                        IngredientMapper ingredientMapper,
                        TacoOfTheDayService tacoOfTheDayService,
                        TacoRatingService tacoRatingService,
                        UserRepository userRepo,
                        @Value("${taco.search.max-page-size:50}") int maxPageSize) {
    this.tacoRepo = tacoRepo;
    this.classificationService = classificationService != null ? classificationService : new TacoClassificationService(ingredientRepo, tacoRepo);
    this.ingredientRepo = ingredientRepo;
    this.designValidator = designValidator;
    this.ingredientMapper = ingredientMapper != null ? ingredientMapper : new IngredientMapper();
    this.tacoOfTheDayService = tacoOfTheDayService != null ? tacoOfTheDayService : new TacoOfTheDayService(tacoRepo, ingredientRepo, designValidator, this.classificationService, this.ingredientMapper, Clock.systemDefaultZone(), "America/Mexico_City");
    this.tacoRatingService = tacoRatingService;
    this.userRepo = userRepo;
    this.maxPageSize = maxPageSize > 0 ? maxPageSize : 50;
  }

  public TacoController(TacoRepository tacoRepo,
                        TacoClassificationService classificationService,
                        IngredientRepository ingredientRepo,
                        TacoDesignValidator designValidator,
                        IngredientMapper ingredientMapper,
                        TacoOfTheDayService tacoOfTheDayService,
                        @Value("${taco.search.max-page-size:50}") int maxPageSize) {
    this(tacoRepo, classificationService, ingredientRepo, designValidator, ingredientMapper, tacoOfTheDayService, null, null, maxPageSize);
  }

  public TacoController(TacoRepository tacoRepo,
                        TacoClassificationService classificationService,
                        IngredientRepository ingredientRepo,
                        TacoDesignValidator designValidator) {
    this(tacoRepo, classificationService, ingredientRepo, designValidator, new IngredientMapper(), null, null, null, 50);
  }

  public TacoController(TacoRepository tacoRepo,
                        TacoClassificationService classificationService,
                        IngredientRepository ingredientRepo) {
    this(tacoRepo, classificationService, ingredientRepo, null, new IngredientMapper(), null, null, null, 50);
  }

  public TacoController(TacoRepository tacoRepo, TacoOfTheDayService tacoOfTheDayService) {
    this(tacoRepo, new TacoClassificationService(null, tacoRepo), null, null, new IngredientMapper(), tacoOfTheDayService, null, null, 50);
  }

  public TacoController(TacoRepository tacoRepo, TacoRatingService tacoRatingService) {
    this(tacoRepo, new TacoClassificationService(null, tacoRepo), null, null, new IngredientMapper(), null, tacoRatingService, null, 50);
  }

  public TacoController(TacoRepository tacoRepo, TacoRatingService tacoRatingService, UserRepository userRepo) {
    this(tacoRepo, new TacoClassificationService(null, tacoRepo), null, null, new IngredientMapper(), null, tacoRatingService, userRepo, 50);
  }

  public TacoController(TacoRepository tacoRepo) {
    this(tacoRepo, new TacoClassificationService(null, tacoRepo), null, null, new IngredientMapper(), null, null, null, 50);
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

  @GetMapping("/top")
  public Mono<List<TopTacoResponse>> getTopTacos(
      @RequestParam(name = "limit", defaultValue = "10") int limit,
      @RequestParam(name = "minVotes", required = false) Integer minVotes) {
    if (tacoRatingService == null) {
      return Mono.just(Collections.emptyList());
    }
    return tacoRatingService.getTopTacos(limit, minVotes);
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

  @PutMapping("/{id}/rating")
  public Mono<TacoRatingSummaryResponse> rateTaco(
      @PathVariable("id") String id,
      @Valid @RequestBody RatingRequest request,
      @AuthenticationPrincipal User user,
      Principal principal,
      Authentication authentication) {

    if (tacoRatingService == null) {
      return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Taco rating service not available"));
    }
    if (request == null || request.getScore() == null) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Score is required"));
    }

    return resolveUserId(user, principal, authentication)
        .flatMap(userId -> tacoRatingService.submitRating(userId, id, request.getScore()));
  }

  @GetMapping("/{id}/rating")
  public Mono<TacoRatingSummaryResponse> getTacoRating(
      @PathVariable("id") String id,
      @AuthenticationPrincipal User user,
      Principal principal,
      Authentication authentication) {

    if (tacoRatingService == null) {
      return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Taco rating service not available"));
    }

    return resolveOptionalUserId(user, principal, authentication)
        .flatMap(userId -> tacoRatingService.getRatingSummary(id, userId));
  }

  private Mono<String> resolveUserId(User user, Principal principal, Authentication authentication) {
    if (user != null && user.getId() != null && !user.getId().trim().isEmpty()) {
      return Mono.just(user.getId());
    }
    String username = null;
    if (user != null && user.getUsername() != null) {
      username = user.getUsername();
    } else if (principal != null && principal.getName() != null) {
      username = principal.getName();
    } else if (authentication != null && authentication.getName() != null) {
      username = authentication.getName();
    }

    if (username == null || username.trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated"));
    }

    if (userRepo != null) {
      return userRepo.findByUsername(username)
          .map(User::getId)
          .defaultIfEmpty(username);
    }

    return Mono.just(username);
  }

  private Mono<String> resolveOptionalUserId(User user, Principal principal, Authentication authentication) {
    return resolveUserId(user, principal, authentication)
        .onErrorResume(ResponseStatusException.class, ex -> Mono.just(""));
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
