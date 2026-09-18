package tacos.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.User;
import tacos.data.UserRepository;

public class RegistrationControllerTest {

  private UserRepository userRepo;
  private PasswordEncoder passwordEncoder;
  private RegistrationController controller;

  @BeforeEach
  public void setUp() {
    userRepo = mock(UserRepository.class);
    passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    controller = new RegistrationController(userRepo, passwordEncoder);
  }

  @Test
  @DisplayName("Registro reactivo REST: debe persistir con password {bcrypt}, componer el publisher y retornar 201 sin password")
  public void registerRest_shouldPersistWithBcryptAndReturn201WithoutPassword() {
    RegistrationForm form = new RegistrationForm();
    form.setUsername("newuser");
    form.setPassword("plainPassword123");
    form.setFullname("New User");
    form.setEmail("newuser@example.com");

    when(userRepo.findByUsername("newuser")).thenReturn(Mono.empty());
    when(userRepo.findByEmail("newuser@example.com")).thenReturn(Mono.empty());

    User savedUser = new User(
        "newuser",
        passwordEncoder.encode("plainPassword123"),
        "New User", null, null, null, null, null, "newuser@example.com"
    );
    savedUser.setId("USER_ID_100");

    when(userRepo.save(any(User.class))).thenReturn(Mono.just(savedUser));

    Mono<ResponseEntity<UserResponse>> responseMono = controller.registerRest(form);

    // Verificación de ejecución perezosa: antes de suscribir, save() NO debe haber sido ejecutado
    verify(userRepo, never()).save(any());

    // Al suscribirse (composición reactiva completa), save() debe completarse y retornar 201 Created
    StepVerifier.create(responseMono)
        .assertNext(response -> {
          assertEquals(HttpStatus.CREATED, response.getStatusCode());
          assertNotNull(response.getBody());
          UserResponse body = response.getBody();
          assertEquals("USER_ID_100", body.getId());
          assertEquals("newuser", body.getUsername());
          assertEquals("newuser@example.com", body.getEmail());
        })
        .verifyComplete();

    // Validar que el usuario guardado tiene el hash {bcrypt}
    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    verify(userRepo, times(1)).save(userCaptor.capture());
    User persistedUser = userCaptor.getValue();
    assertEquals("newuser", persistedUser.getUsername());
    assertTrue(persistedUser.getPassword().startsWith("{bcrypt}"));
    assertTrue(passwordEncoder.matches("plainPassword123", persistedUser.getPassword()));
  }

  @Test
  @DisplayName("Registro formulario HTML: debe redireccionar a /login sólo después de completar el guardado reactivo")
  public void processRegistration_formSubmission_shouldRedirectOnlyAfterSave() {
    RegistrationForm form = new RegistrationForm();
    form.setUsername("webuser");
    form.setPassword("pass1234");
    form.setEmail("webuser@example.com");

    when(userRepo.findByUsername("webuser")).thenReturn(Mono.empty());
    when(userRepo.findByEmail("webuser@example.com")).thenReturn(Mono.empty());

    User saved = new User("webuser", passwordEncoder.encode("pass1234"), null, null, null, null, null, null, "webuser@example.com");
    when(userRepo.save(any(User.class))).thenReturn(Mono.just(saved));

    Mono<String> resultMono = controller.processRegistration(form);

    verify(userRepo, never()).save(any());

    StepVerifier.create(resultMono)
        .expectNext("redirect:/login")
        .verifyComplete();

    verify(userRepo, times(1)).save(any(User.class));
  }

  @Test
  @DisplayName("Unicidad de username previa: si el username ya existe, debe rechazar con 409 CONFLICT sin guardar")
  public void register_duplicateUsername_shouldThrowConflict409() {
    RegistrationForm form = new RegistrationForm();
    form.setUsername("existinguser");
    form.setPassword("password123");
    form.setEmail("user@example.com");

    User existing = new User("existinguser", "{bcrypt}...", null, null, null, null, null, null, "other@example.com");
    when(userRepo.findByUsername("existinguser")).thenReturn(Mono.just(existing));

    Mono<ResponseEntity<UserResponse>> responseMono = controller.registerRest(form);

    StepVerifier.create(responseMono)
        .expectErrorMatches(throwable -> throwable instanceof ResponseStatusException
            && ((ResponseStatusException) throwable).getStatus() == HttpStatus.CONFLICT
            && throwable.getMessage().contains("Username 'existinguser' is already taken"))
        .verify();

    verify(userRepo, never()).save(any());
  }

  @Test
  @DisplayName("Unicidad de email previa: si el email ya existe, debe rechazar con 409 CONFLICT sin guardar")
  public void register_duplicateEmail_shouldThrowConflict409() {
    RegistrationForm form = new RegistrationForm();
    form.setUsername("anotheruser");
    form.setPassword("password123");
    form.setEmail("taken@example.com");

    when(userRepo.findByUsername("anotheruser")).thenReturn(Mono.empty());
    User existing = new User("firstuser", "{bcrypt}...", null, null, null, null, null, null, "taken@example.com");
    when(userRepo.findByEmail("taken@example.com")).thenReturn(Mono.just(existing));

    Mono<ResponseEntity<UserResponse>> responseMono = controller.registerRest(form);

    StepVerifier.create(responseMono)
        .expectErrorMatches(throwable -> throwable instanceof ResponseStatusException
            && ((ResponseStatusException) throwable).getStatus() == HttpStatus.CONFLICT
            && throwable.getMessage().contains("Email 'taken@example.com' is already registered"))
        .verify();

    verify(userRepo, never()).save(any());
  }

  @Test
  @DisplayName("Condición de carrera / Índice único: DuplicateKeyException de base de datos debe mapearse a 409 CONFLICT")
  public void register_raceCondition_duplicateKeyException_shouldBeMappedTo409Conflict() {
    RegistrationForm form = new RegistrationForm();
    form.setUsername("concurrentuser");
    form.setPassword("password123");
    form.setEmail("concurrent@example.com");

    // Ambos pre-checks pasan porque los dos hilos consultaron simultáneamente antes del commit
    when(userRepo.findByUsername("concurrentuser")).thenReturn(Mono.empty());
    when(userRepo.findByEmail("concurrent@example.com")).thenReturn(Mono.empty());

    // Al llegar a MongoDB, el índice único detecta la colisión y lanza DuplicateKeyException
    when(userRepo.save(any(User.class)))
        .thenReturn(Mono.error(new DuplicateKeyException("E11000 duplicate key error collection: test.user index: username dup key")));

    Mono<ResponseEntity<UserResponse>> responseMono = controller.registerRest(form);

    StepVerifier.create(responseMono)
        .expectErrorMatches(throwable -> throwable instanceof ResponseStatusException
            && ((ResponseStatusException) throwable).getStatus() == HttpStatus.CONFLICT
            && throwable.getMessage().contains("Username or email already exists"))
        .verify();
  }
}
