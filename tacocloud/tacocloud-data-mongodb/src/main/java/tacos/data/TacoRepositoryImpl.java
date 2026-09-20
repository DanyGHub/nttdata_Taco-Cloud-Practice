package tacos.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.search.TacoPage;
import tacos.search.TacoSearchCriteria;

@Repository
public class TacoRepositoryImpl implements TacoRepositoryCustom {

  private static final Set<String> SORT_WHITELIST = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList("createdAt", "name", "id"))
  );

  private final ReactiveMongoTemplate mongoTemplate;

  @Autowired
  public TacoRepositoryImpl(ReactiveMongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public Mono<TacoPage<Taco>> searchTacos(TacoSearchCriteria criteria) {
    if (criteria == null) {
      criteria = TacoSearchCriteria.builder().build();
    }

    int page = Math.max(0, criteria.getPage());
    int size = criteria.getSize() > 0 ? criteria.getSize() : 20;

    Sort sort = parseAndValidateSort(criteria.getSort());

    List<Criteria> criteriaList = new ArrayList<>();

    // 1. Filtro por nombre (Texto con escaping seguro anti-ReDoS)
    if (criteria.hasNameFilter()) {
      String rawName = criteria.getName().trim();
      if (rawName.length() > 100) {
        rawName = rawName.substring(0, 100);
      }
      String safePattern = Pattern.quote(rawName);
      criteriaList.add(Criteria.where("name").regex(".*" + safePattern + ".*", "i"));
    }

    // 2. Filtro por ingrediente
    if (criteria.hasIngredientFilter()) {
      String ingId = criteria.getIngredientId().trim();
      criteriaList.add(new Criteria().orOperator(
          Criteria.where("ingredients._id").is(ingId),
          Criteria.where("ingredients.id").is(ingId)
      ));
    }

    // 3. Filtro por etiqueta dietaria
    if (criteria.hasDietFilter()) {
      criteriaList.add(new Criteria().orOperator(
          Criteria.where("dietaryTags").is(criteria.getDiet()),
          Criteria.where("dietaryTags").is(criteria.getDiet().name())
      ));
    }

    // 4. Filtro de exclusión de alérgeno
    if (criteria.hasExcludeAllergenFilter()) {
      criteriaList.add(new Criteria().andOperator(
          Criteria.where("allergens").ne(criteria.getExcludeAllergen()),
          Criteria.where("allergens").ne(criteria.getExcludeAllergen().name())
      ));
    }

    // 5. Filtro por nivel de picante
    if (criteria.hasSpiceFilter()) {
      criteriaList.add(new Criteria().orOperator(
          Criteria.where("spiceLevel").is(criteria.getSpice()),
          Criteria.where("spiceLevel").is(criteria.getSpice().name())
      ));
    }

    Query baseQuery = new Query();
    if (!criteriaList.isEmpty()) {
      baseQuery.addCriteria(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
    }

    Query countQuery = Query.of(baseQuery);

    Query dataQuery = Query.of(baseQuery)
        .with(sort)
        .skip((long) page * size)
        .limit(size);

    Mono<Long> totalElementsMono = mongoTemplate.count(countQuery, Taco.class);
    Mono<List<Taco>> contentMono = mongoTemplate.find(dataQuery, Taco.class).collectList();

    return Mono.zip(totalElementsMono, contentMono)
        .map(tuple -> TacoPage.of(tuple.getT2(), page, size, tuple.getT1()));
  }

  private Sort parseAndValidateSort(String sortParam) {
    if (sortParam == null || sortParam.trim().isEmpty()) {
      return Sort.by(Sort.Direction.DESC, "createdAt")
          .and(Sort.by(Sort.Direction.DESC, "id"));
    }

    String[] parts = sortParam.trim().split(",");
    String field = parts[0].trim();
    if (!SORT_WHITELIST.contains(field)) {
      throw new IllegalArgumentException(String.format(
          "Sort field '%s' is not permitted. Allowed fields: %s", field, SORT_WHITELIST
      ));
    }

    Sort.Direction direction = Sort.Direction.ASC;
    if (parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())) {
      direction = Sort.Direction.DESC;
    } else if (parts.length == 1 && "createdAt".equalsIgnoreCase(field)) {
      direction = Sort.Direction.DESC;
    }

    // Paginación estable: orden primario con desempate secundario por ID
    Sort primarySort = Sort.by(direction, field);
    Sort tieBreaker = Sort.by(direction, "id");
    return primarySort.and(tieBreaker);
  }
}
