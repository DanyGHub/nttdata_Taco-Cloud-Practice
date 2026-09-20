package tacos.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.classification.TacoClassification;
import tacos.classification.TacoClassificationService;
import tacos.data.TacoRatingRepository;
import tacos.data.TacoRepository;
import tacos.web.api.dto.IngredientMapper;
import tacos.web.api.dto.TacoRatingSummaryResponse;
import tacos.web.api.dto.TacoResponse;
import tacos.web.api.dto.TopTacoResponse;

@Service
public class TacoRatingService {

  public static final String RATINGS_COLLECTION = "taco_ratings";

  private final TacoRatingRepository ratingRepo;
  private final TacoRepository tacoRepo;
  private final ReactiveMongoTemplate mongoTemplate;
  private final IngredientMapper ingredientMapper;
  private final TacoClassificationService classificationService;
  private final int defaultMinVotes;

  @Autowired
  public TacoRatingService(TacoRatingRepository ratingRepo,
                           TacoRepository tacoRepo,
                           ReactiveMongoTemplate mongoTemplate,
                           IngredientMapper ingredientMapper,
                           TacoClassificationService classificationService,
                           @Value("${taco.rating.min-votes:1}") int defaultMinVotes) {
    this.ratingRepo = ratingRepo;
    this.tacoRepo = tacoRepo;
    this.mongoTemplate = mongoTemplate;
    this.ingredientMapper = ingredientMapper != null ? ingredientMapper : new IngredientMapper();
    this.classificationService = classificationService;
    this.defaultMinVotes = Math.max(1, defaultMinVotes);
  }

  public TacoRatingService(TacoRatingRepository ratingRepo,
                           TacoRepository tacoRepo,
                           ReactiveMongoTemplate mongoTemplate) {
    this(ratingRepo, tacoRepo, mongoTemplate, new IngredientMapper(), null, 1);
  }

  public Mono<TacoRatingSummaryResponse> submitRating(String userId, String tacoId, int score) {
    if (userId == null || userId.trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User must be authenticated to submit rating"));
    }
    if (tacoId == null || tacoId.trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Taco ID is required"));
    }
    if (score < 1 || score > 5) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Score must be between 1 and 5"));
    }

    return tacoRepo.findById(tacoId)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Taco not found with id: " + tacoId)))
        .flatMap(taco -> {
          if (!taco.isPublished()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Taco is not published for rating"));
          }

          return ratingRepo.findByUserIdAndTacoId(userId, tacoId)
              .flatMap(existingRating -> {
                existingRating.setScore(score);
                existingRating.setUpdatedAt(new Date());
                return ratingRepo.save(existingRating);
              })
              .switchIfEmpty(
                  Mono.defer(() -> {
                    TacoRating newRating = new TacoRating(userId, tacoId, score);
                    return ratingRepo.insert(newRating)
                        .onErrorResume(DuplicateKeyException.class, ex ->
                            ratingRepo.findByUserIdAndTacoId(userId, tacoId)
                                .flatMap(concurrentRating -> {
                                  concurrentRating.setScore(score);
                                  concurrentRating.setUpdatedAt(new Date());
                                  return ratingRepo.save(concurrentRating);
                                })
                        );
                  })
              )
              .then(getRatingSummary(tacoId, userId));
        });
  }

  public Mono<TacoRatingSummaryResponse> getRatingSummary(String tacoId, String optionalUserId) {
    if (tacoId == null || tacoId.trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Taco ID is required"));
    }

    Aggregation aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("tacoId").is(tacoId)),
        buildGroupOperation()
    );

    Mono<TacoRatingAggregationResult> aggregateMono = mongoTemplate.aggregate(aggregation, RATINGS_COLLECTION, TacoRatingAggregationResult.class)
        .next()
        .defaultIfEmpty(emptyResult(tacoId));

    Mono<Integer> userScoreMono = (optionalUserId != null && !optionalUserId.trim().isEmpty())
        ? ratingRepo.findByUserIdAndTacoId(optionalUserId, tacoId)
            .map(TacoRating::getScore)
            .defaultIfEmpty(0)
        : Mono.just(0);

    return Mono.zip(aggregateMono, userScoreMono)
        .map(tuple -> {
          TacoRatingAggregationResult agg = tuple.getT1();
          int userScore = tuple.getT2();

          Map<Integer, Long> distribution = new LinkedHashMap<>();
          distribution.put(1, agg.getScore1());
          distribution.put(2, agg.getScore2());
          distribution.put(3, agg.getScore3());
          distribution.put(4, agg.getScore4());
          distribution.put(5, agg.getScore5());

          return TacoRatingSummaryResponse.builder()
              .tacoId(tacoId)
              .averageScore(roundToTwoDecimals(agg.getAverageScore()))
              .totalVotes(agg.getTotalVotes())
              .distribution(distribution)
              .userScore(userScore > 0 ? userScore : null)
              .build();
        });
  }

  public Mono<List<TopTacoResponse>> getTopTacos(int limit, Integer minVotesParam) {
    int effectiveLimit = (limit > 0 && limit <= 50) ? limit : 10;
    int effectiveMinVotes = (minVotesParam != null && minVotesParam >= 0) ? minVotesParam : defaultMinVotes;

    Aggregation aggregation = Aggregation.newAggregation(
        buildGroupOperation(),
        Aggregation.match(Criteria.where("totalVotes").gte(effectiveMinVotes)),
        Aggregation.sort(
            Sort.by(
                Sort.Order.desc("averageScore"),
                Sort.Order.desc("totalVotes"),
                Sort.Order.desc("score5"),
                Sort.Order.asc("_id")
            )
        ),
        Aggregation.limit(effectiveLimit)
    );

    return mongoTemplate.aggregate(aggregation, RATINGS_COLLECTION, TacoRatingAggregationResult.class)
        .collectList()
        .flatMap(results -> {
          if (results.isEmpty()) {
            return Mono.just(Collections.emptyList());
          }

          List<String> tacoIds = results.stream()
              .map(TacoRatingAggregationResult::getId)
              .filter(Objects::nonNull)
              .collect(Collectors.toList());

          // Single batch query across MongoDB (elimina N+1)
          return tacoRepo.findAllById(tacoIds)
              .filter(Taco::isPublished)
              .collectMap(Taco::getId, taco -> taco)
              .map(tacoMap -> {
                List<TopTacoResponse> topTacos = new ArrayList<>();
                int rank = 1;

                for (TacoRatingAggregationResult agg : results) {
                  Taco taco = tacoMap.get(agg.getId());
                  if (taco != null) {
                    Map<Integer, Long> distribution = new LinkedHashMap<>();
                    distribution.put(1, agg.getScore1());
                    distribution.put(2, agg.getScore2());
                    distribution.put(3, agg.getScore3());
                    distribution.put(4, agg.getScore4());
                    distribution.put(5, agg.getScore5());

                    topTacos.add(TopTacoResponse.builder()
                        .rank(rank++)
                        .taco(toTacoResponse(taco))
                        .averageScore(roundToTwoDecimals(agg.getAverageScore()))
                        .totalVotes(agg.getTotalVotes())
                        .distribution(distribution)
                        .build());
                  }
                }
                return topTacos;
              });
        });
  }

  private AggregationOperation buildGroupOperation() {
    String groupJson = "{" +
        "\"$group\": {" +
          "\"_id\": \"$tacoId\"," +
          "\"totalVotes\": { \"$sum\": 1 }," +
          "\"averageScore\": { \"$avg\": \"$score\" }," +
          "\"score1\": { \"$sum\": { \"$cond\": [{ \"$eq\": [\"$score\", 1] }, 1, 0] } }," +
          "\"score2\": { \"$sum\": { \"$cond\": [{ \"$eq\": [\"$score\", 2] }, 1, 0] } }," +
          "\"score3\": { \"$sum\": { \"$cond\": [{ \"$eq\": [\"$score\", 3] }, 1, 0] } }," +
          "\"score4\": { \"$sum\": { \"$cond\": [{ \"$eq\": [\"$score\", 4] }, 1, 0] } }," +
          "\"score5\": { \"$sum\": { \"$cond\": [{ \"$eq\": [\"$score\", 5] }, 1, 0] } }" +
        "}" +
      "}";
    return context -> Document.parse(groupJson);
  }

  private TacoRatingAggregationResult emptyResult(String tacoId) {
    TacoRatingAggregationResult res = new TacoRatingAggregationResult();
    res.setId(tacoId);
    res.setAverageScore(0.0);
    res.setTotalVotes(0L);
    return res;
  }

  private double roundToTwoDecimals(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return 0.0;
    }
    return BigDecimal.valueOf(value)
        .setScale(2, RoundingMode.HALF_UP)
        .doubleValue();
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

  @Data
  @NoArgsConstructor
  public static class TacoRatingAggregationResult {
    private String id;
    private long totalVotes;
    private double averageScore;
    private long score1;
    private long score2;
    private long score3;
    private long score4;
    private long score5;
  }

}
