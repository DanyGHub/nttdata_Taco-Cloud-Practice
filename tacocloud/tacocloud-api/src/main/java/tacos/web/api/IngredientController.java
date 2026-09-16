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

@RestController
@RequestMapping(path="/api/ingredients", produces="application/json")
@CrossOrigin(origins="http://localhost:8080")
public class IngredientController {

  private IngredientRepository repo;

  @Autowired
  public IngredientController(IngredientRepository repo) {
    this.repo = repo;
  }

  @GetMapping
  public Flux<Ingredient> allIngredients() {
    return repo.findAll();
  }

  @GetMapping("/{id}")
  public Mono<Ingredient> byId(@PathVariable String id) {
    return repo.findById(id);
  }

  // TC-01 — Actualizar un ingrediente sin perder el publisher
  @PutMapping("/{id}")
  public Mono<ResponseEntity<Ingredient>> updateIngredient(@PathVariable String id, @RequestBody Ingredient ingredient) {
    if (ingredient.getId() == null || !ingredient.getId().equals(id))
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ingredient's ID doesn't match the ID in the path.")); // Status 400

    return repo.findById(id)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Ingredient Not Found"))) // Status 404
        .flatMap(existing -> repo.save(ingredient))
        .map(saved -> ResponseEntity.ok(saved));    // Status 200
  }

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
  public Mono<ResponseEntity<Ingredient>> postIngredient(@RequestBody(required = false) Ingredient ingredient) {
    if (ingredient == null 
        || ingredient.getId() == null || ingredient.getId().trim().isEmpty()
        || ingredient.getName() == null || ingredient.getName().trim().isEmpty()
        || ingredient.getType() == null) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ingredient: id, name, and type are required"));  // Status 400
    }

    return repo.save(ingredient)
        .map(saved -> {
          URI location = UriComponentsBuilder.fromPath("/api/ingredients/{id}")
              .buildAndExpand(saved.getId())
              .toUri();
          return ResponseEntity.created(location).body(saved);
        });
  }

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