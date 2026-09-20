package tacos.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.data.IngredientRepository;
import tacos.data.TacoRepository;
import tacos.web.api.dto.TacoOfTheDayResponse;

@DisplayName("TC-20: Motor Determinista de Taco del Día")
public class TacoOfTheDayServiceTest {

  private TacoRepository tacoRepo;
  private IngredientRepository ingredientRepo;
  private ZoneId zoneId;

  private Ingredient ingWrap;
  private Ingredient ingProtein;
  private Ingredient ingProtein2;
  private Ingredient ingSauce;

  @BeforeEach
  public void setup() {
    tacoRepo = mock(TacoRepository.class);
    ingredientRepo = mock(IngredientRepository.class);
    zoneId = ZoneId.of("America/Mexico_City");

    ingWrap = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, BigDecimal.valueOf(0.50), true, 100, 10, null);
    ingProtein = new Ingredient("CARN", "Carnitas", Type.PROTEIN, BigDecimal.valueOf(1.50), true, 50, 10, null);
    ingProtein2 = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN, BigDecimal.valueOf(1.50), true, 50, 10, null);
    ingSauce = new Ingredient("SLSA", "Salsa", Type.SAUCE, BigDecimal.valueOf(0.30), true, 80, 10, null);

    when(ingredientRepo.findAllById(anyList())).thenAnswer(invocation -> {
      List<String> requestedIds = invocation.getArgument(0);
      List<Ingredient> matched = new ArrayList<>();
      for (String id : requestedIds) {
        if ("FLTO".equals(id)) matched.add(ingWrap);
        else if ("CARN".equals(id)) matched.add(ingProtein);
        else if ("GRBF".equals(id)) matched.add(ingProtein2);
        else if ("SLSA".equals(id)) matched.add(ingSauce);
      }
      return Flux.fromIterable(matched);
    });
  }

  private Taco createTestTaco(String id, String name) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName(name);
    taco.setIngredients(Arrays.asList(ingWrap, ingProtein, ingSauce));
    return taco;
  }

  @Test
  @DisplayName("Dos llamadas el mismo día retornan el mismo taco")
  public void shouldReturnSameTacoOnMultipleCallsSameDayWithFixedClock() {
    Clock fixedClock = Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), zoneId);
    LocalDate expectedDate = LocalDate.now(fixedClock.withZone(zoneId));

    Taco t1 = createTestTaco("taco-1", "Carnitas Fiesta");
    Taco t2 = createTestTaco("taco-2", "Pollo Asado");
    Taco t3 = createTestTaco("taco-3", "Al Pastor");

    when(tacoRepo.findAll()).thenReturn(Flux.just(t1, t2, t3));

    TacoOfTheDayService service = new TacoOfTheDayService(tacoRepo, ingredientRepo, null, fixedClock, zoneId);

    TacoOfTheDayResponse resp1 = service.getTacoOfTheDay().block();
    assertThat(resp1).isNotNull();
    assertThat(resp1.getDate()).isEqualTo(expectedDate);
    assertThat(resp1.getReason()).contains("porque hoy es");
    assertThat(resp1.getTaco()).isNotNull();

    String firstTacoId = resp1.getTaco().getId();

    TacoOfTheDayResponse resp2 = service.getTacoOfTheDay().block();
    assertThat(resp2).isNotNull();
    assertThat(resp2.getTaco().getId()).isEqualTo(firstTacoId);
    assertThat(resp2.getDate()).isEqualTo(expectedDate);
  }

  @Test
  @DisplayName("Cambiar Clock al día siguiente cambia de forma predecible y cíclica")
  public void shouldPredictablyChangeTacoNextDayWhenAdvancingClock() {
    Instant day1Instant = Instant.parse("2026-09-20T10:00:00Z");
    Instant day2Instant = Instant.parse("2026-09-21T10:00:00Z");

    Clock clockDay1 = Clock.fixed(day1Instant, zoneId);
    Clock clockDay2 = Clock.fixed(day2Instant, zoneId);

    Taco t1 = createTestTaco("taco-1", "Taco 1");
    Taco t2 = createTestTaco("taco-2", "Taco 2");

    when(tacoRepo.findAll()).thenReturn(Flux.just(t1, t2));

    TacoOfTheDayService serviceDay1 = new TacoOfTheDayService(tacoRepo, ingredientRepo, null, clockDay1, zoneId);
    TacoOfTheDayResponse respDay1 = serviceDay1.getTacoOfTheDay().block();

    TacoOfTheDayService serviceDay2 = new TacoOfTheDayService(tacoRepo, ingredientRepo, null, clockDay2, zoneId);
    TacoOfTheDayResponse respDay2 = serviceDay2.getTacoOfTheDay().block();

    assertThat(respDay1).isNotNull();
    assertThat(respDay2).isNotNull();
    assertThat(respDay1.getDate()).isEqualTo(LocalDate.parse("2026-09-20"));
    assertThat(respDay2.getDate()).isEqualTo(LocalDate.parse("2026-09-21"));

    assertThat(respDay1.getTaco().getId()).isNotEqualTo(respDay2.getTaco().getId());
  }

  @Test
  @DisplayName("Dos listas con distinto orden físico producen exactamente el mismo resultado")
  public void shouldProduceIdenticalResultRegardlessOfPhysicalListOrder() {
    Clock fixedClock = Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), zoneId);

    Taco t1 = createTestTaco("taco-1", "Taco Uno");
    Taco t2 = createTestTaco("taco-2", "Taco Dos");
    Taco t3 = createTestTaco("taco-3", "Taco Tres");

    when(tacoRepo.findAll()).thenReturn(Flux.just(t3, t1, t2));
    TacoOfTheDayService service1 = new TacoOfTheDayService(tacoRepo, ingredientRepo, null, fixedClock, zoneId);
    TacoOfTheDayResponse resp1 = service1.getTacoOfTheDay().block();

    when(tacoRepo.findAll()).thenReturn(Flux.just(t2, t3, t1));
    TacoOfTheDayService service2 = new TacoOfTheDayService(tacoRepo, ingredientRepo, null, fixedClock, zoneId);
    TacoOfTheDayResponse resp2 = service2.getTacoOfTheDay().block();

    assertThat(resp1).isNotNull();
    assertThat(resp2).isNotNull();
    assertThat(resp1.getTaco().getId()).isEqualTo(resp2.getTaco().getId());
    assertThat(resp1.getTaco().getName()).isEqualTo(resp2.getTaco().getName());
  }

  @Test
  @DisplayName("Sin candidatos retorna vacío (Mono.empty)")
  public void shouldReturnEmptyWhenNoCandidatesExist() {
    Clock fixedClock = Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), zoneId);

    when(tacoRepo.findAll()).thenReturn(Flux.empty());

    TacoOfTheDayService service = new TacoOfTheDayService(tacoRepo, ingredientRepo, null, fixedClock, zoneId);

    StepVerifier.create(service.getTacoOfTheDay())
        .verifyComplete();
  }

  @Test
  @DisplayName("Taco no disponible no se recomienda y se invalida la caché")
  public void shouldInvalidateCacheAndNotRecommendUnavailableTaco() {
    Clock fixedClock = Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), zoneId);

    Taco t1 = createTestTaco("taco-1", "Taco Con Carnitas");
    Taco t2 = new Taco();
    t2.setId("taco-2");
    t2.setName("Taco Con Carne Molida");
    t2.setIngredients(Arrays.asList(ingWrap, ingProtein2, ingSauce));

    when(tacoRepo.findAll()).thenReturn(Flux.just(t1, t2));

    TacoOfTheDayService service = new TacoOfTheDayService(tacoRepo, ingredientRepo, null, fixedClock, zoneId);

    TacoOfTheDayResponse firstResp = service.getTacoOfTheDay().block();
    assertThat(firstResp).isNotNull();
    String firstSelectedId = firstResp.getTaco().getId();

    if ("taco-1".equals(firstSelectedId)) {
      ingProtein.setAvailable(false);
    } else {
      ingProtein2.setAvailable(false);
    }

    TacoOfTheDayResponse secondResp = service.getTacoOfTheDay().block();
    assertThat(secondResp).isNotNull();
    assertThat(secondResp.getTaco().getId()).isNotEqualTo(firstSelectedId);
  }

  @Test
  @DisplayName("La explicación no inventa datos sensibles e incluye 'porque hoy es'")
  public void shouldIncludeCleanReasonWithoutSensitiveData() {
    Clock fixedClock = Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), zoneId);
    Taco t1 = createTestTaco("taco-1", "Especial Supremo");
    when(tacoRepo.findAll()).thenReturn(Flux.just(t1));

    TacoOfTheDayService service = new TacoOfTheDayService(tacoRepo, ingredientRepo, null, fixedClock, zoneId);
    TacoOfTheDayResponse resp = service.getTacoOfTheDay().block();

    assertThat(resp).isNotNull();
    assertThat(resp.getReason()).contains("porque hoy es");
    assertThat(resp.getReason()).contains("Especial Supremo");
    assertThat(resp.getReason()).doesNotContain("password");
    assertThat(resp.getReason()).doesNotContain("profit");
    assertThat(resp.getReason()).doesNotContain("cost");
  }

}
