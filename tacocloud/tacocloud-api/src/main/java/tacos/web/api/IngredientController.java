package tacos.web.api;

import java.net.URI;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.http.server.reactive.ServerHttpRequest;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.data.IngredientRepository;
import tacos.web.api.dto.IngredientMapper;
import tacos.web.api.dto.IngredientRequest;
import tacos.web.api.dto.IngredientResponse;

@RestController
@RequestMapping(path="/api/ingredients", produces="application/json")
@CrossOrigin(origins="http://localhost:8080")
public class IngredientController {

  private IngredientRepository repo;
  private IngredientMapper mapper;

  @Autowired
  public IngredientController(IngredientRepository repo, IngredientMapper mapper) {
    this.repo = repo;
    this.mapper = mapper != null ? mapper : new IngredientMapper();
  }

  public IngredientController(IngredientRepository repo) {
    this(repo, new IngredientMapper());
  }

  @GetMapping
  public Flux<IngredientResponse> allIngredients() {
    return repo.findAll().map(mapper::toResponse);
  }

  @GetMapping("/{id}")
  public Mono<IngredientResponse> byId(@PathVariable String id) {
    return repo.findById(id).map(mapper::toResponse);
  }

  // TC-01 — Actualizar un ingrediente sin perder el publisher
  @PutMapping("/{id}")
  public Mono<ResponseEntity<IngredientResponse>> updateIngredient(@PathVariable String id, @RequestBody IngredientRequest ingredient) {
    if (ingredient == null || ingredient.getId() == null || !ingredient.getId().equals(id))
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ingredient's ID doesn't match the ID in the path.")); // Status 400

    return repo.findById(id)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingredient Not Found"))) // Status 404
        .flatMap(existing -> repo.save(mapper.toDomain(ingredient)))
        .map(saved -> ResponseEntity.ok(mapper.toResponse(saved)));    // Status 200
  }

  /*
  // Llamada directa TC-01 (Mantenida comentada para referencia o regresión)
  public Mono<ResponseEntity<Ingredient>> updateIngredient(String id, Ingredient ingredient) {
    if (ingredient == null || ingredient.getId() == null || !ingredient.getId().equals(id))
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ingredient's ID doesn't match the ID in the path."));

    IngredientRequest req = new IngredientRequest(ingredient.getId(), ingredient.getName(), ingredient.getType());
    return updateIngredient(id, req)
        .map(resp -> ResponseEntity.status(resp.getStatusCode()).headers(resp.getHeaders()).body(mapper.toDomain(resp.getBody())));
  }
  */

  /*
  @PutMapping("/{id}")
  public void updateIngredient(@PathVariable String id, @RequestBody Ingredient ingredient) {
    if (!ingredient.getId().equals(id)) {
      throw new IllegalStateException("Given ingredient's ID doesn't match the ID in the path.");
    }
    repo.save(ingredient);
  }
  */

  // TC-03 Construir Location sin localhost ni rutas rotas
  @PostMapping
  public Mono<ResponseEntity<IngredientResponse>> postIngredient(@RequestBody(required = false) IngredientRequest ingredient) {
    if (ingredient == null 
        || ingredient.getId() == null || ingredient.getId().trim().isEmpty()
        || ingredient.getName() == null || ingredient.getName().trim().isEmpty()
        || ingredient.getType() == null) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ingredient: id, name, and type are required"));  // Status 400
    }

    return repo.save(mapper.toDomain(ingredient))
        .map(saved -> {
          URI location = UriComponentsBuilder.fromPath("/api/ingredients/{id}")
              .buildAndExpand(saved.getId())
              .toUri();
          return ResponseEntity.created(location).body(mapper.toResponse(saved));
        });
  }

  /*
  // Llamada directa TC-03 (Mantenida comentada para referencia o regresión)
  public Mono<ResponseEntity<Ingredient>> postIngredient(Ingredient ingredient) {
    if (ingredient == null 
        || ingredient.getId() == null || ingredient.getId().trim().isEmpty()
        || ingredient.getName() == null || ingredient.getName().trim().isEmpty()
        || ingredient.getType() == null) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ingredient: id, name, and type are required"));
    }
    IngredientRequest req = new IngredientRequest(ingredient.getId(), ingredient.getName(), ingredient.getType());
    return postIngredient(req)
        .map(resp -> ResponseEntity.status(resp.getStatusCode()).headers(resp.getHeaders()).body(mapper.toDomain(resp.getBody())));
  }
  */

  /*
  @PostMapping
  public Mono<ResponseEntity<Ingredient>> postIngredient(@RequestBody Mono<Ingredient> ingredient) {
    return ingredient
        .flatMap(repo::save)
        .map(i -> {
          HttpHeaders headers = new HttpHeaders();
          headers.setLocation(URI.create("http://localhost:8080/ingredients/" + i.getId()));
          return new ResponseEntity<Ingredient>(i, headers, HttpStatus.CREATED);
        });
  }
  */

  // TC-02 — Eliminar de verdad y responder con semántica HTTP
  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<Void>> deleteIngredient(@PathVariable String id) {
    return repo.existsById(id)
        .flatMap(exists -> {
          if (exists) {
            return repo.deleteById(id)
                .thenReturn(new ResponseEntity<Void>(HttpStatus.NO_CONTENT)); // Status 204
          } else {
            return Mono.just(new ResponseEntity<Void>(HttpStatus.NOT_FOUND)); // Status 404
          }
        });
  }

  /*
  @DeleteMapping("/{id}")
  public void deleteIngredient(@PathVariable String id) {
    repo.deleteById(id);
  }
  */
}