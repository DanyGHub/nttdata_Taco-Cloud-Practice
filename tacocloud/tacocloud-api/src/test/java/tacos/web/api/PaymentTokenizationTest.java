package tacos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.PaymentMethod;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;
import tacos.payment.FakePaymentGateway;
import tacos.payment.PaymentGateway;
import tacos.payment.TokenizeResult;
import tacos.web.api.dto.OrderCreateRequest;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.PaymentMethodResponse;
import tacos.web.api.dto.TacoRequest;
import tacos.web.api.dto.TokenizeRequest;

public class PaymentTokenizationTest {

  private PaymentGateway paymentGateway;
  private PaymentMethodRepository paymentMethodRepo;
  private UserRepository userRepo;
  private OrderRepository orderRepo;
  private OrderMessagingService messagingService;
  private EmailOrderService emailOrderService;
  private OrderMapper orderMapper;

  private PaymentMethodController paymentController;
  private OrderApiController orderApiController;

  private ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  public void setUp() {
    paymentGateway = new FakePaymentGateway();
    paymentMethodRepo = mock(PaymentMethodRepository.class);
    userRepo = mock(UserRepository.class);
    orderRepo = mock(OrderRepository.class);
    messagingService = mock(OrderMessagingService.class);
    emailOrderService = mock(EmailOrderService.class);
    orderMapper = new OrderMapper();

    paymentController = new PaymentMethodController(paymentGateway, paymentMethodRepo, userRepo);
    orderApiController = new OrderApiController(orderRepo, messagingService, emailOrderService,
        orderMapper, userRepo, paymentMethodRepo, paymentGateway);
  }

  @Test
  @DisplayName("FakePaymentGateway: Tokeniza tarjetas sintéticas y detecta marca sin guardar CVV")
  public void fakePaymentGateway_shouldTokenizeCorrectlyForVariousBrands() {
    // VISA
    TokenizeResult visaResult = paymentGateway.tokenize("4111222233334444", "12/28", "123").block();
    assertThat(visaResult).isNotNull();
    assertThat(visaResult.getBrand()).isEqualTo("VISA");
    assertThat(visaResult.getLast4()).isEqualTo("4444");
    assertThat(visaResult.getPaymentToken()).startsWith("tok_visa_");
    assertThat(visaResult.getExpiration()).isEqualTo("12/28");

    // Mastercard
    TokenizeResult mcResult = paymentGateway.tokenize("5500000000009999", "10/26", "456").block();
    assertThat(mcResult.getBrand()).isEqualTo("MASTERCARD");
    assertThat(mcResult.getLast4()).isEqualTo("9999");
    assertThat(mcResult.getPaymentToken()).startsWith("tok_mastercard_");

    // AMEX
    TokenizeResult amexResult = paymentGateway.tokenize("340000000003005", "05/27", "7890").block();
    assertThat(amexResult.getBrand()).isEqualTo("AMEX");
    assertThat(amexResult.getLast4()).isEqualTo("3005");
    assertThat(amexResult.getPaymentToken()).startsWith("tok_amex_");

    // Discover
    TokenizeResult discoverResult = paymentGateway.tokenize("6011000000001111", "01/29", "111").block();
    assertThat(discoverResult.getBrand()).isEqualTo("DISCOVER");
    assertThat(discoverResult.getLast4()).isEqualTo("1111");

    // Generic
    TokenizeResult genericResult = paymentGateway.tokenize("9999888877776666", "11/25", "333").block();
    assertThat(genericResult.getBrand()).isEqualTo("GENERIC");
    assertThat(genericResult.getLast4()).isEqualTo("6666");
  }

  @Test
  @DisplayName("POST /api/payment-methods/tokenize: Genera token seguro, persiste método y nunca expone token completo ni CVV")
  public void tokenizeEndpoint_shouldSavePaymentMethodAndReturnMaskedToken() {
    User user = new User("habuma", "pass", "Craig Walls", "123 Main St", "City", "TX", "75001", "555", "craig@test.com", Arrays.asList("ROLE_USER"));
    user.setId("USER_10");

    when(userRepo.findByUsername("habuma")).thenReturn(Mono.just(user));
    when(paymentMethodRepo.save(any(PaymentMethod.class))).thenAnswer(invocation -> {
      PaymentMethod pm = invocation.getArgument(0);
      pm.setId("PM_123");
      return Mono.just(pm);
    });

    Authentication auth = new UsernamePasswordAuthenticationToken("habuma", null,
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

    TokenizeRequest req = new TokenizeRequest("4111111111111111", "12/28", "123", "Craig Walls");

    StepVerifier.create(paymentController.tokenize(req, auth))
        .assertNext(resp -> {
          assertThat(resp.getId()).isEqualTo("PM_123");
          assertThat(resp.getBrand()).isEqualTo("VISA");
          assertThat(resp.getLast4()).isEqualTo("1111");
          assertThat(resp.getExpiration()).isEqualTo("12/28");
          assertThat(resp.getUsername()).isEqualTo("habuma");
          // El token completo NUNCA se expone
          assertThat(resp.getMaskedToken()).isEqualTo("tok_***1111");
        })
        .verifyComplete();

    // Verificar lo que se guardó en PaymentMethod
    ArgumentCaptor<PaymentMethod> pmCaptor = ArgumentCaptor.forClass(PaymentMethod.class);
    verify(paymentMethodRepo).save(pmCaptor.capture());
    PaymentMethod saved = pmCaptor.getValue();
    assertThat(saved.getBrand()).isEqualTo("VISA");
    assertThat(saved.getLast4()).isEqualTo("1111");
    assertThat(saved.getPaymentToken()).startsWith("tok_visa_");
  }

  @Test
  @DisplayName("POST /api/orders: Crea orden con paymentMethodId tokenizado sin datos sensibles")
  public void postOrder_withPaymentMethodId_shouldCreateOrderWithBrandAndLast4() {
    User user = new User("habuma", "pass", "Craig Walls", "123 Main St", "City", "TX", "75001", "555", "craig@test.com", Arrays.asList("ROLE_USER"));
    PaymentMethod pm = new PaymentMethod(user, "tok_secure_12345", "VISA", "4111", "12/28");
    pm.setId("PM_SAVED");

    when(userRepo.findByUsername("habuma")).thenReturn(Mono.just(user));
    when(paymentMethodRepo.findById("PM_SAVED")).thenReturn(Mono.just(pm));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> {
      TacoOrder ord = inv.getArgument(0);
      ord.setId("ORD_999");
      return Mono.just(ord);
    });

    Authentication auth = new UsernamePasswordAuthenticationToken("habuma", null,
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

    OrderCreateRequest req = new OrderCreateRequest();
    req.setDeliveryName("Craig Walls");
    req.setDeliveryStreet("123 Main St");
    req.setDeliveryCity("Dallas");
    req.setDeliveryState("TX");
    req.setDeliveryZip("75001");
    req.setPaymentMethodId("PM_SAVED");

    TacoRequest tacoReq = new TacoRequest();
    tacoReq.setName("Taco Token");
    req.setTacos(Collections.singletonList(tacoReq));

    StepVerifier.create(orderApiController.postOrder(req, auth))
        .assertNext(resp -> {
          assertThat(resp.getId()).isEqualTo("ORD_999");
          assertThat(resp.getBrand()).isEqualTo("VISA");
          assertThat(resp.getLast4()).isEqualTo("4111");
        })
        .verifyComplete();

    // Verificar que el TacoOrder guardado tiene token y brand/last4 pero NO ccNumber ni ccCVV
    ArgumentCaptor<TacoOrder> orderCaptor = ArgumentCaptor.forClass(TacoOrder.class);
    verify(orderRepo).save(orderCaptor.capture());
    TacoOrder savedOrder = orderCaptor.getValue();
    assertThat(savedOrder.getPaymentMethodId()).isEqualTo("PM_SAVED");
    assertThat(savedOrder.getPaymentToken()).isEqualTo("tok_secure_12345");
    assertThat(savedOrder.getBrand()).isEqualTo("VISA");
    assertThat(savedOrder.getLast4()).isEqualTo("4111");
  }

  @Test
  @DisplayName("Serialización Segura: OrderResponse muestra únicamente brand/last4 y no revela PAN, CVV ni token")
  public void orderResponse_mustOnlyExposeBrandAndLast4_neverPanOrCvv() throws Exception {
    OrderResponse response = new OrderResponse();
    response.setId("ORD_1");
    response.setDeliveryName("Customer");
    response.setBrand("VISA");
    response.setLast4("4111");

    String json = objectMapper.writeValueAsString(response);

    assertThat(json).contains("\"brand\":\"VISA\"");
    assertThat(json).contains("\"last4\":\"4111\"");
    assertThat(json).doesNotContain("ccNumber");
    assertThat(json).doesNotContain("ccCVV");
    assertThat(json).doesNotContain("ccExpiration");
    assertThat(json).doesNotContain("paymentToken");
  }

  @Test
  @DisplayName("Redacción de logs: TokenizeRequest.toString() enmascara PAN y redacta CVV")
  public void tokenizeRequest_toString_mustMaskCardAndCvv() {
    TokenizeRequest req = new TokenizeRequest("4111222233334444", "12/28", "999", "John Doe");
    String str = req.toString();

    assertThat(str).contains("cardNumber=****-****-****-4444");
    assertThat(str).contains("cvv=***");
    assertThat(str).doesNotContain("4111222233334444");
    assertThat(str).doesNotContain("999");
  }

  @Test
  @DisplayName("Evento de cocina: El objeto TacoOrder enviado por mensajería contiene cero campos de PAN o CVV y omite paymentToken")
  public void kitchenEvent_mustContainZeroSensitivePaymentFields() throws Exception {
    Field[] fields = TacoOrder.class.getDeclaredFields();
    for (Field f : fields) {
      assertThat(f.getName()).isNotEqualTo("ccNumber");
      assertThat(f.getName()).isNotEqualTo("ccCVV");
      assertThat(f.getName()).isNotEqualTo("ccExpiration");
    }

    TacoOrder order = new TacoOrder();
    order.setPaymentToken("tok_secret_12345");
    order.setBrand("VISA");
    order.setLast4("4111");
    String json = objectMapper.writeValueAsString(order);
    assertThat(json).doesNotContain("tok_secret_12345");
    assertThat(json).doesNotContain("paymentToken");
  }
}
