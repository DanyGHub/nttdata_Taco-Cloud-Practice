package tacos.web.api;

import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.data.IngredientRepository;
import tacos.web.api.dto.AdminIngredientResponse;
import tacos.web.api.dto.IngredientCatalogUpdateRequest;
import tacos.web.api.dto.IngredientMapper;
import tacos.web.api.dto.StockAdjustmentRequest;

@RestController
@RequestMapping(path = "/api/admin/ingredients", produces = "application/json")
@CrossOrigin(origins = "*")
public class AdminIngredientController {

  private static final Logger log = LoggerFactory.getLogger(AdminIngredientController.class);

  private final IngredientRepository repo;
  private final IngredientMapper mapper;

  @Autowired
  public AdminIngredientController(IngredientRepository repo, IngredientMapper mapper) {
    this.repo = repo;
    this.mapper = mapper != null ? mapper : new IngredientMapper();
  }

  public AdminIngredientController(IngredientRepository repo) {
    this(repo, new IngredientMapper());
  }

  @GetMapping("/{id}")
  public Mono<ResponseEntity<AdminIngredientResponse>> getById(@PathVariable String id) {
    return repo.findById(id)
        .map(ingredient -> ResponseEntity.ok(mapper.toAdminResponse(ingredient)))
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingredient not found: " + id)));
  }

  @PatchMapping("/{id}/catalog")
  public Mono<ResponseEntity<AdminIngredientResponse>> updateCatalog(
      @PathVariable String id,
      @Valid @RequestBody IngredientCatalogUpdateRequest request) {
    return repo.findById(id)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingredient not found: " + id)))
        .flatMap(ingredient -> {
          if (request.getVersion() != null && !request.getVersion().equals(ingredient.getVersion())) {
            log.warn("Optimistic locking conflict on ingredient {}: expected={}, actual={}",
                id, request.getVersion(), ingredient.getVersion());
            return Mono.error(new OptimisticLockingFailureException(
                "Optimistic locking failure: expected version " + request.getVersion() + " but found " + ingredient.getVersion()));
          }
          if (request.getUnitPrice() != null) {
            ingredient.setUnitPrice(request.getUnitPrice());
          }
          if (request.getAvailable() != null) {
            ingredient.setAvailable(request.getAvailable());
          }
          return repo.save(ingredient);
        })
        .map(saved -> {
          log.info("Updated catalog for ingredient {}: unitPrice={}, available={}, version={}",
              saved.getId(), saved.getUnitPrice(), saved.isAvailable(), saved.getVersion());
          return ResponseEntity.ok(mapper.toAdminResponse(saved));
        });
  }

  @PostMapping("/{id}/stock-adjustments")
  public Mono<ResponseEntity<AdminIngredientResponse>> adjustStock(
      @PathVariable String id,
      @Valid @RequestBody StockAdjustmentRequest request) {
    return repo.findById(id)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingredient not found: " + id)))
        .flatMap(ingredient -> {
          if (request.getExpectedVersion() != null && !request.getExpectedVersion().equals(ingredient.getVersion())) {
            log.warn("Optimistic locking conflict on stock adjustment for {}: expected={}, actual={}",
                id, request.getExpectedVersion(), ingredient.getVersion());
            return Mono.error(new OptimisticLockingFailureException(
                "Optimistic locking failure: expected version " + request.getExpectedVersion() + " but found " + ingredient.getVersion()));
          }

          int currentStock = ingredient.getStockOnHand();
          int adjustment = request.getAmount();
          int newStock = currentStock + adjustment;

          if (newStock < 0) {
            log.warn("Stock adjustment rejected for {}: current={}, adjustment={}, wouldBeNegative={}",
                id, currentStock, adjustment, newStock);
            return Mono.error(new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Stock adjustment rejected: resulting stock cannot be negative (current=" + currentStock + ", adjustment=" + adjustment + ")"));
          }

          ingredient.setStockOnHand(newStock);
          return repo.save(ingredient);
        })
        .map(saved -> {
          log.info("Stock adjusted for ingredient {}: newStock={}, version={}, reason={}",
              saved.getId(), saved.getStockOnHand(), saved.getVersion(), request.getReason());
          return ResponseEntity.ok(mapper.toAdminResponse(saved));
        });
  }

}
