package tacos.security;

import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.User;
import tacos.data.UserRepository;

@Controller
@RequestMapping({"/register", "/api/users"})
public class RegistrationController {

  private static final Logger log = LoggerFactory.getLogger(RegistrationController.class);

  private final UserRepository userRepo;
  private final PasswordEncoder passwordEncoder;

  public RegistrationController(
      UserRepository userRepo, PasswordEncoder passwordEncoder) {
    this.userRepo = userRepo;
    this.passwordEncoder = passwordEncoder;
  }

  @GetMapping
  public String registerForm() {
    return "registration";
  }

  // TC-10: Registro reactivo con contraseñas protegidas

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @ResponseBody
  public Mono<ResponseEntity<UserResponse>> registerRest(@Valid @RequestBody RegistrationForm form) {
    return registerUser(form)
        .map(savedUser -> ResponseEntity.status(HttpStatus.CREATED).body(toResponse(savedUser)));
  }

  @PostMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.ALL_VALUE})
  public Mono<String> processRegistration(RegistrationForm form) {
    return registerUser(form)
        .thenReturn("redirect:/login");
  }

  /*
  // @PostMapping
  // public String processRegistration(RegistrationForm form) {
  //   userRepo.save(form.toUser(passwordEncoder));
  //   return "redirect:/login";
  // }
  */

  private Mono<User> registerUser(RegistrationForm form) {
    if (form == null || form.getUsername() == null || form.getUsername().trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is required"));
    }
    if (form.getPassword() == null || form.getPassword().trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is required"));
    }

    String username = form.getUsername().trim();
    String email = form.getEmail() != null ? form.getEmail().trim() : null;

    return userRepo.findByUsername(username)
        .flatMap(existing -> Mono.<User>error(
            new ResponseStatusException(HttpStatus.CONFLICT, "Username '" + username + "' is already taken")
        ))
        .switchIfEmpty(Mono.defer(() -> {
          if (email != null && !email.isEmpty()) {
            return userRepo.findByEmail(email)
                .flatMap(existing -> Mono.<User>error(
                    new ResponseStatusException(HttpStatus.CONFLICT, "Email '" + email + "' is already registered")
                ));
          }
          return Mono.empty();
        }))
        .then(Mono.defer(() -> {
          User userToSave = form.toUser(passwordEncoder);
          log.info("Registering user '{}' with encrypted credentials", username);
          return userRepo.save(userToSave);
        }))
        .onErrorMap(ex -> ex instanceof DuplicateKeyException
            || ex instanceof DataIntegrityViolationException
            || (ex.getMessage() != null && (ex.getMessage().contains("duplicate key") || ex.getMessage().contains("E11000"))),
            ex -> {
              log.warn("Race condition duplicate key detected for user '{}': {}", username, ex.getMessage());
              return new ResponseStatusException(HttpStatus.CONFLICT, "Username or email already exists", ex);
            });
  }

  private UserResponse toResponse(User user) {
    return new UserResponse(
        user.getId(),
        user.getUsername(),
        user.getFullname(),
        user.getStreet(),
        user.getCity(),
        user.getState(),
        user.getZip(),
        user.getPhoneNumber(),
        user.getEmail()
    );
  }

}
