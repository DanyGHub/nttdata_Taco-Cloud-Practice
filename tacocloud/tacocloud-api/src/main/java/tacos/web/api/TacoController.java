package tacos.web.api;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.classification.TacoClassificationService;
import tacos.data.IngredientRepository;
import tacos.data.TacoRepository;
import tacos.web.api.dto.TacoClassificationResponse;

@RestController
@RequestMapping(path = "/api/tacos", produces = "application/json")
@CrossOrigin(origins="http://localhost:8080")
public class TacoController {

  private final TacoRepository tacoRepo;
  private final TacoClassificationService classificationService;
  private final IngredientRepository ingredientRepo;

  @Autowired
  public TacoController(TacoRepository tacoRepo,
                        TacoClassificationService classificationService,
                        IngredientRepository ingredientRepo) {
    this.tacoRepo = tacoRepo;
    this.classificationService = classificationService != null ? classificationService : new TacoClassificationService(ingredientRepo, tacoRepo);
    this.ingredientRepo = ingredientRepo;
  }

  public TacoController(TacoRepository tacoRepo) {
    this(tacoRepo, new TacoClassificationService(null, tacoRepo), null);
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
              return tacoRepo.save(taco);
            });
      }
    }

    return tacoRepo.save(taco);
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

}
