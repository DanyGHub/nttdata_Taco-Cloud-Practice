package tacos.web.api;

import java.security.Principal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.User;
import tacos.data.UserRepository;
import tacos.favorites.FavoriteService;
import tacos.search.TacoPage;
import tacos.web.api.dto.FavoriteResponse;

@RestController
@RequestMapping(path = "/api/users/me/favorites", produces = "application/json")
@CrossOrigin(origins = "http://localhost:8080")
public class UserFavoritesController {

  private final FavoriteService favoriteService;
  private final UserRepository userRepo;

  @Autowired
  public UserFavoritesController(FavoriteService favoriteService, UserRepository userRepo) {
    this.favoriteService = favoriteService;
    this.userRepo = userRepo;
  }

  public UserFavoritesController(FavoriteService favoriteService) {
    this(favoriteService, null);
  }

  @PutMapping("/{tacoId}")
  public Mono<ResponseEntity<FavoriteResponse>> addFavorite(
      @PathVariable("tacoId") String tacoId,
      @AuthenticationPrincipal User user,
      Principal principal,
      Authentication authentication) {

    return resolveUserId(user, principal, authentication)
        .flatMap(userId -> favoriteService.addFavorite(userId, tacoId))
        .map(ResponseEntity::ok);
  }

  @DeleteMapping("/{tacoId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> removeFavorite(
      @PathVariable("tacoId") String tacoId,
      @AuthenticationPrincipal User user,
      Principal principal,
      Authentication authentication) {

    return resolveUserId(user, principal, authentication)
        .flatMap(userId -> favoriteService.removeFavorite(userId, tacoId));
  }

  @GetMapping
  public Mono<TacoPage<FavoriteResponse>> getFavorites(
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size,
      @AuthenticationPrincipal User user,
      Principal principal,
      Authentication authentication) {

    return resolveUserId(user, principal, authentication)
        .flatMap(userId -> favoriteService.getFavorites(userId, page, size));
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

}
