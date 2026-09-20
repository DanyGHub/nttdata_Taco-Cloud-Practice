package tacos.favorites;

import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.classification.TacoClassification;
import tacos.classification.TacoClassificationService;
import tacos.data.FavoriteRepository;
import tacos.data.TacoRepository;
import tacos.search.TacoPage;
import tacos.web.api.dto.FavoriteResponse;
import tacos.web.api.dto.IngredientMapper;
import tacos.web.api.dto.TacoResponse;

@Service
public class FavoriteService {

  private final FavoriteRepository favoriteRepo;
  private final TacoRepository tacoRepo;
  private final TacoClassificationService classificationService;
  private final IngredientMapper ingredientMapper;

  @Autowired
  public FavoriteService(FavoriteRepository favoriteRepo,
                         TacoRepository tacoRepo,
                         TacoClassificationService classificationService,
                         IngredientMapper ingredientMapper) {
    this.favoriteRepo = favoriteRepo;
    this.tacoRepo = tacoRepo;
    this.classificationService = classificationService != null ? classificationService : new TacoClassificationService(null, tacoRepo);
    this.ingredientMapper = ingredientMapper != null ? ingredientMapper : new IngredientMapper();
  }

  public FavoriteService(FavoriteRepository favoriteRepo, TacoRepository tacoRepo) {
    this(favoriteRepo, tacoRepo, null, new IngredientMapper());
  }

  public Mono<FavoriteResponse> addFavorite(String userId, String tacoId) {
    if (userId == null || userId.trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated"));
    }
    if (tacoId == null || tacoId.trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Taco ID must not be blank"));
    }

    //Verify that the taco exists in the database
    return tacoRepo.findById(tacoId)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Taco not found with id: " + tacoId)))
        .flatMap(taco ->
            favoriteRepo.findByUserIdAndTacoId(userId, tacoId)
                .map(existing -> toFavoriteResponse(existing, taco))
                .switchIfEmpty(Mono.defer(() -> {
                  Favorite newFav = new Favorite(userId, tacoId);
                  return favoriteRepo.save(newFav)
                      .onErrorResume(DuplicateKeyException.class, ex ->
                          favoriteRepo.findByUserIdAndTacoId(userId, tacoId)
                      )
                      .map(saved -> toFavoriteResponse(saved, taco));
                }))
        );
  }

  public Mono<Void> removeFavorite(String userId, String tacoId) {
    if (userId == null || userId.trim().isEmpty() || tacoId == null || tacoId.trim().isEmpty()) {
      return Mono.empty();
    }
    // Idempotent delete
    return favoriteRepo.deleteByUserIdAndTacoId(userId, tacoId);
  }

  public Mono<TacoPage<FavoriteResponse>> getFavorites(String userId, int page, int size) {
    if (userId == null || userId.trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated"));
    }
    if (page < 0) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page index must not be less than zero"));
    }
    if (size < 1) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size must be greater than zero"));
    }
    int effectiveSize = Math.min(size, 50);

    Pageable pageable = PageRequest.of(page, effectiveSize, Sort.by(Sort.Direction.DESC, "createdAt"));

    return favoriteRepo.findByUserId(userId, pageable)
        .flatMap(fav -> tacoRepo.findById(fav.getTacoId())
            .map(taco -> toFavoriteResponse(fav, taco))
            .switchIfEmpty(Mono.defer(() -> {
              // Taco was deleted from database -> clean up orphan favorite
              return favoriteRepo.delete(fav).then(Mono.empty());
            }))
        )
        .collectList()
        .zipWith(favoriteRepo.countByUserId(userId))
        .map(tuple -> TacoPage.of(tuple.getT1(), page, effectiveSize, tuple.getT2()));
  }

  public Mono<Boolean> isFavorite(String userId, String tacoId) {
    if (userId == null || tacoId == null) {
      return Mono.just(false);
    }
    return favoriteRepo.existsByUserIdAndTacoId(userId, tacoId);
  }

  private FavoriteResponse toFavoriteResponse(Favorite fav, Taco taco) {
    if (fav == null) {
      return null;
    }
    return FavoriteResponse.builder()
        .id(fav.getId())
        .tacoId(fav.getTacoId())
        .taco(toTacoResponse(taco))
        .createdAt(fav.getCreatedAt())
        .build();
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
          .filter(Objects::nonNull)
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
