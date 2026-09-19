package tacos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.PaymentMethod;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.web.api.EmailOrder.EmailTaco;

public class EmailOrderServiceTest {

  private UserRepository userRepo;
  private IngredientRepository ingredientRepo;
  private PaymentMethodRepository paymentMethodRepo;
  private EmailOrderService emailOrderService;

  @BeforeEach
  public void setUp() {
    userRepo = mock(UserRepository.class);
    ingredientRepo = mock(IngredientRepository.class);
    paymentMethodRepo = mock(PaymentMethodRepository.class);
    emailOrderService = new EmailOrderService(userRepo, ingredientRepo, paymentMethodRepo);
  }

  // TC-06: Pruebas mínimas requeridas por el documento

  @Test
  public void shouldConvertEmailOrderWithMultipleTacosSuccessfully() {
    User user = new User(
      "craig", 
      "password", 
      "Craig Walls", 
      "123 North Street",
      "Cross Roads", 
      "TX", 
      "76227", 
      "123-123-1234", 
      "craig@habuma.com");
    user.setId("USER_1");

    PaymentMethod payment = new PaymentMethod(user, "tok_fake_1111", "VISA", "4444", "01/30");

    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
    Ingredient carn = new Ingredient("CARN", "Carnitas", Type.PROTEIN);
    Ingredient coto = new Ingredient("COTO", "Corn Tortilla", Type.WRAP);
    Ingredient tmto = new Ingredient("TMTO", "Diced Tomatoes", Type.VEGGIES);
    Ingredient jack = new Ingredient("JACK", "Monterrey Jack", Type.CHEESE);

    when(userRepo.findByEmail("craig@habuma.com")).thenReturn(Mono.just(user));
    when(paymentMethodRepo.findByUserId("USER_1")).thenReturn(Mono.just(payment));
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(flto));
    when(ingredientRepo.findById("CARN")).thenReturn(Mono.just(carn));
    when(ingredientRepo.findById("COTO")).thenReturn(Mono.just(coto));
    when(ingredientRepo.findById("TMTO")).thenReturn(Mono.just(tmto));
    when(ingredientRepo.findById("JACK")).thenReturn(Mono.just(jack));

    EmailOrder emailOrder = new EmailOrder();
    emailOrder.setEmail("craig@habuma.com");

    EmailTaco taco1 = new EmailTaco();
    taco1.setName("Carnitas Taco");
    taco1.setIngredients(Arrays.asList("FLTO", "CARN", "JACK"));

    EmailTaco taco2 = new EmailTaco();
    taco2.setName("Veggie Taco");
    taco2.setIngredients(Arrays.asList("COTO", "TMTO"));

    emailOrder.setTacos(Arrays.asList(taco1, taco2));

    Mono<TacoOrder> orderMono = emailOrderService.convertEmailOrderToDomainOrder(Mono.just(emailOrder));

    StepVerifier.create(orderMono)
        .assertNext(order -> {
          assertThat(order.getUser()).isEqualTo(user);
          assertThat(order.getDeliveryName()).isEqualTo("Craig Walls");
          assertThat(order.getDeliveryStreet()).isEqualTo("123 North Street");
          assertThat(order.getDeliveryCity()).isEqualTo("Cross Roads");
          assertThat(order.getDeliveryState()).isEqualTo("TX");
          assertThat(order.getDeliveryZip()).isEqualTo("76227");

          assertThat(order.getPaymentToken()).isEqualTo("tok_fake_1111");
          assertThat(order.getBrand()).isEqualTo("VISA");
          assertThat(order.getLast4()).isEqualTo("4444");
          assertThat(order.getPlacedAt()).isNotNull();

          assertThat(order.getTacos()).hasSize(2);

          Taco firstTaco = order.getTacos().get(0);
          assertThat(firstTaco.getName()).isEqualTo("Carnitas Taco");
          assertThat(firstTaco.getIngredients()).containsExactly(flto, carn, jack);

          Taco secondTaco = order.getTacos().get(1);
          assertThat(secondTaco.getName()).isEqualTo("Veggie Taco");
          assertThat(secondTaco.getIngredients()).containsExactly(coto, tmto);
        })
        .verifyComplete();
  }

  @Test
  public void shouldFailWhenIngredientDoesNotExist() {
    User user = new User(
      "craig", 
      "password", 
      "Craig Walls", 
      "123 North Street",
      "Cross Roads", 
      "TX", 
      "76227", 
      "123-123-1234", 
      "craig@habuma.com");
    user.setId("USER_1");
    PaymentMethod payment = new PaymentMethod(user, "tok_fake_1111", "VISA", "4444", "01/30");

    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);

    when(userRepo.findByEmail("craig@habuma.com")).thenReturn(Mono.just(user));
    when(paymentMethodRepo.findByUserId("USER_1")).thenReturn(Mono.just(payment));
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(flto));
    when(ingredientRepo.findById("UNKNOWN")).thenReturn(Mono.empty()); // Ingrediente no encontrado

    EmailOrder emailOrder = new EmailOrder();
    emailOrder.setEmail("craig@habuma.com");

    EmailTaco taco = new EmailTaco();
    taco.setName("Faulty Taco");
    taco.setIngredients(Arrays.asList("FLTO", "UNKNOWN"));
    emailOrder.setTacos(Collections.singletonList(taco));

    Mono<TacoOrder> orderMono = emailOrderService.convertEmailOrderToDomainOrder(Mono.just(emailOrder));

    StepVerifier.create(orderMono)
        .expectErrorMatches(throwable ->
            throwable instanceof IllegalArgumentException && throwable.getMessage().contains("Unknown ingredient ID: UNKNOWN"))
        .verify();
  }

  @Test
  public void shouldFailWhenUserNotFound() {
    when(userRepo.findByEmail("missing@example.com")).thenReturn(Mono.empty());

    EmailOrder emailOrder = new EmailOrder();
    emailOrder.setEmail("missing@example.com");
    emailOrder.setTacos(Collections.emptyList());

    Mono<TacoOrder> orderMono = emailOrderService.convertEmailOrderToDomainOrder(Mono.just(emailOrder));

    StepVerifier.create(orderMono)
        .expectErrorMatches(throwable -> throwable instanceof IllegalStateException &&
            throwable.getMessage().contains("User not found for email: missing@example.com"))
        .verify();
  }

  @Test
  public void shouldFailWhenPaymentMethodNotFound() {
    User user = new User(
      "craig", 
      "password", 
      "Craig Walls", 
      "123 North Street",
      "Cross Roads", 
      "TX", 
      "76227", 
      "123-123-1234", 
      "craig@habuma.com");
    user.setId("USER_NO_PAY");

    when(userRepo.findByEmail("craig@habuma.com")).thenReturn(Mono.just(user));
    when(paymentMethodRepo.findByUserId("USER_NO_PAY")).thenReturn(Mono.empty());

    EmailOrder emailOrder = new EmailOrder();
    emailOrder.setEmail("craig@habuma.com");
    emailOrder.setTacos(Collections.emptyList());

    Mono<TacoOrder> orderMono = emailOrderService.convertEmailOrderToDomainOrder(Mono.just(emailOrder));

    StepVerifier.create(orderMono)
        .expectErrorMatches(throwable ->
            throwable instanceof IllegalStateException &&
            throwable.getMessage().contains("Payment method not found for user ID: USER_NO_PAY"))
        .verify();
  }

  @Test
  public void shouldConvertEmailOrderWithEmptyTacosListSuccessfully() {
    User user = new User(
      "craig", 
      "password", 
      "Craig Walls", 
      "123 North Street",
      "Cross Roads", 
      "TX", 
      "76227", 
      "123-123-1234", 
      "craig@habuma.com");
    user.setId("USER_1");
    PaymentMethod payment = new PaymentMethod(user, "tok_fake_1111", "VISA", "4444", "01/30");

    when(userRepo.findByEmail("craig@habuma.com")).thenReturn(Mono.just(user));
    when(paymentMethodRepo.findByUserId("USER_1")).thenReturn(Mono.just(payment));

    EmailOrder emailOrder = new EmailOrder();
    emailOrder.setEmail("craig@habuma.com");
    emailOrder.setTacos(Collections.emptyList());

    Mono<TacoOrder> orderMono = emailOrderService.convertEmailOrderToDomainOrder(Mono.just(emailOrder));

    StepVerifier.create(orderMono)
        .assertNext(order -> {
          assertThat(order.getUser()).isEqualTo(user);
          assertThat(order.getTacos()).isEmpty();
        })
        .verifyComplete();
  }

  @Test
  public void shouldPreserveFifoOrderWithThreeTacos() {
    User user = new User("craig", "password", "Craig Walls", "123 North Street",
        "Cross Roads", "TX", "76227", "123-123-1234", "craig@habuma.com");
    user.setId("USER_1");
    PaymentMethod payment = new PaymentMethod(user, "tok_fake_1111", "VISA", "4444", "01/30");

    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
    Ingredient coto = new Ingredient("COTO", "Corn Tortilla", Type.WRAP);
    Ingredient carn = new Ingredient("CARN", "Carnitas", Type.PROTEIN);

    when(userRepo.findByEmail("craig@habuma.com")).thenReturn(Mono.just(user));
    when(paymentMethodRepo.findByUserId("USER_1")).thenReturn(Mono.just(payment));
    when(ingredientRepo.findById("FLTO")).thenReturn(Mono.just(flto));
    when(ingredientRepo.findById("COTO")).thenReturn(Mono.just(coto));
    when(ingredientRepo.findById("CARN")).thenReturn(Mono.just(carn));

    EmailTaco t1 = new EmailTaco();
    t1.setName("Taco Uno");
    t1.setIngredients(Collections.singletonList("FLTO"));

    EmailTaco t2 = new EmailTaco();
    t2.setName("Taco Dos");
    t2.setIngredients(Collections.singletonList("COTO"));

    EmailTaco t3 = new EmailTaco();
    t3.setName("Taco Tres");
    t3.setIngredients(Collections.singletonList("CARN"));

    EmailOrder emailOrder = new EmailOrder();
    emailOrder.setEmail("craig@habuma.com");
    emailOrder.setTacos(Arrays.asList(t1, t2, t3));

    StepVerifier.create(emailOrderService.convertEmailOrderToDomainOrder(Mono.just(emailOrder)))
        .assertNext(order -> {
          assertThat(order.getTacos()).hasSize(3);
          assertThat(order.getTacos().get(0).getName()).isEqualTo("Taco Uno");
          assertThat(order.getTacos().get(1).getName()).isEqualTo("Taco Dos");
          assertThat(order.getTacos().get(2).getName()).isEqualTo("Taco Tres");
        })
        .verifyComplete();
  }
}