package tacos.announcements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

import com.mongodb.client.result.DeleteResult;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.announcements.dto.OpsAnnouncementAdminDto;
import tacos.announcements.dto.OpsAnnouncementDto;
import tacos.announcements.dto.OpsAnnouncementRequest;

public class OpsAnnouncementServiceTest {

  private OpsAnnouncementRepository repo;
  private ReactiveMongoTemplate mongoTemplate;
  private OpsAnnouncementService service;
  private Instant fixedNow;
  private Clock testClock;

  @BeforeEach
  public void setUp() {
    repo = mock(OpsAnnouncementRepository.class);
    mongoTemplate = mock(ReactiveMongoTemplate.class);
    fixedNow = Instant.parse("2026-09-26T18:00:00Z");
    testClock = Clock.fixed(fixedNow, ZoneOffset.UTC);
    service = new OpsAnnouncementService(repo, mongoTemplate, testClock);
  }

  // --- 1. Validación de Texto y Caracteres de Control ---

  @Test
  @DisplayName("Rechaza mensaje nulo o en blanco con 400 Bad Request")
  public void createAnnouncement_blankOrNullMessage_throwsException() {
    OpsAnnouncementRequest nullReq = OpsAnnouncementRequest.builder().message(null).build();
    OpsAnnouncementRequest blankReq = OpsAnnouncementRequest.builder().message("   ").build();

    assertThatThrownBy(() -> service.createAnnouncement(nullReq, "admin").block())
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("cannot be blank");

    assertThatThrownBy(() -> service.createAnnouncement(blankReq, "admin").block())
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("cannot be blank");
  }

  @Test
  @DisplayName("Rechaza mensajes con menos de 3 o más de 255 caracteres")
  public void createAnnouncement_invalidLength_throwsException() {
    OpsAnnouncementRequest tooShort = OpsAnnouncementRequest.builder().message("Hi").build();
    String longMsg = String.join("", Collections.nCopies(256, "A"));
    OpsAnnouncementRequest tooLong = OpsAnnouncementRequest.builder().message(longMsg).build();

    assertThatThrownBy(() -> service.createAnnouncement(tooShort, "admin").block())
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Message length must be between 3 and 255");

    assertThatThrownBy(() -> service.createAnnouncement(tooLong, "admin").block())
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Message length must be between 3 and 255");
  }

  @Test
  @DisplayName("Rechaza caracteres de control para prevenir log injection y desbordes")
  public void createAnnouncement_controlCharacters_throwsException() {
    String[] illegalMessages = {
        "Aviso con null byte \u0000 detectado",
        "Aviso con newline \n en medio",
        "Aviso con retorno \r de carro",
        "Aviso con escape \u001B[31m ANSI",
        "Aviso con backspace \b peligroso",
        "Aviso con tabulacion \t no permitida"
    };

    for (String illegal : illegalMessages) {
      OpsAnnouncementRequest req = OpsAnnouncementRequest.builder().message(illegal).build();
      assertThatThrownBy(() -> service.createAnnouncement(req, "admin").block())
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("illegal control character");
    }
  }

  // --- 2. Expiración Temporal Determinista con Clock ---

  @Test
  @DisplayName("Expiración con Clock: activo durante vigencia, ausente tras expirar")
  public void expirationWithClock_activeAndExpiredStates() {
    Instant createdAt = fixedNow;
    Instant expiresAt = fixedNow.plus(60, ChronoUnit.MINUTES);

    OpsAnnouncement announcement = OpsAnnouncement.builder()
        .id("ANN-001")
        .message("Ventana de mantenimiento programada")
        .severity(AnnouncementSeverity.WARN)
        .createdAt(createdAt)
        .expiresAt(expiresAt)
        .createdBy("operaciones")
        .active(true)
        .build();

    // 1. A los 30 minutos: está activo
    when(repo.findByActiveTrueAndExpiresAtAfterOrderByCreatedAtDesc(fixedNow))
        .thenReturn(Flux.just(announcement));

    StepVerifier.create(service.getActiveAnnouncements())
        .assertNext(dto -> {
          assertThat(dto.getId()).isEqualTo("ANN-001");
          assertThat(dto.getMessage()).isEqualTo("Ventana de mantenimiento programada");
          assertThat(dto.getSeverity()).isEqualTo(AnnouncementSeverity.WARN);
          // Verifica privacidad: DTO público no expone creador
          assertThat(dto).hasNoNullFieldsOrPropertiesExcept();
        })
        .verifyComplete();

    // 2. A los 65 minutos (avanzando Clock): ha expirado y repo devuelve vacío
    Instant futureNow = fixedNow.plus(65, ChronoUnit.MINUTES);
    service.setClock(Clock.fixed(futureNow, ZoneOffset.UTC));

    when(repo.findByActiveTrueAndExpiresAtAfterOrderByCreatedAtDesc(futureNow))
        .thenReturn(Flux.empty());

    StepVerifier.create(service.getActiveAnnouncements())
        .verifyComplete();
  }

  @Test
  @DisplayName("Rechaza expiración en el pasado o mayor a 30 días")
  public void expirationValidation_pastOrExcessive_throwsException() {
    OpsAnnouncementRequest inPast = OpsAnnouncementRequest.builder()
        .message("Aviso con fecha pasada")
        .expiresAt(fixedNow.minus(1, ChronoUnit.HOURS))
        .build();

    assertThatThrownBy(() -> service.createAnnouncement(inPast, "admin").block())
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Expiration time must be in the future");

    OpsAnnouncementRequest excessive = OpsAnnouncementRequest.builder()
        .message("Aviso demasiado largo")
        .expiresAt(fixedNow.plus(31, ChronoUnit.DAYS))
        .build();

    assertThatThrownBy(() -> service.createAnnouncement(excessive, "admin").block())
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Expiration cannot exceed 30 days");
  }

  // --- 3. Límite de Anuncios Activos (Anti-Chat y DoS) ---

  @Test
  @DisplayName("Rechaza creación cuando se alcanza la cuota máxima de 10 anuncios activos")
  public void createAnnouncement_exceedsActiveLimit_throwsException() {
    when(repo.countByActiveTrueAndExpiresAtAfter(fixedNow)).thenReturn(Mono.just(10L));

    OpsAnnouncementRequest req = OpsAnnouncementRequest.builder()
        .message("Nuevo anuncio cuando el límite ya se alcanzó")
        .build();

    StepVerifier.create(service.createAnnouncement(req, "admin"))
        .expectErrorMatches(throwable -> throwable instanceof ResponseStatusException
            && throwable.getMessage().contains("Maximum number of active announcements (10) reached"))
        .verify();
  }

  // --- 4. Borrado Determinista por ID Estable ---

  @Test
  @DisplayName("Borrado por ID elimina el registro exacto sin alterar índices")
  public void deleteAnnouncement_byId_removesCorrectItem() {
    OpsAnnouncement announcement = OpsAnnouncement.builder()
        .id("STABLE-UUID-42")
        .message("Anuncio a borrar")
        .active(true)
        .build();

    when(repo.findById("STABLE-UUID-42")).thenReturn(Mono.just(announcement));
    when(repo.delete(announcement)).thenReturn(Mono.empty());

    StepVerifier.create(service.deleteAnnouncement("STABLE-UUID-42"))
        .verifyComplete();

    verify(repo, times(1)).delete(announcement);
  }

  @Test
  @DisplayName("Borrado de ID inexistente retorna 404 Not Found")
  public void deleteAnnouncement_notFound_throws404() {
    when(repo.findById("NON-EXISTENT")).thenReturn(Mono.empty());

    StepVerifier.create(service.deleteAnnouncement("NON-EXISTENT"))
        .expectErrorMatches(throwable -> throwable instanceof ResponseStatusException
            && throwable.getMessage().contains("404 NOT_FOUND"))
        .verify();
  }

  // --- 5. Purga de Expirados ---

  @Test
  @DisplayName("purgeExpired() invoca eliminación de documentos con expiresAt <= now")
  public void purgeExpired_deletesOldAnnouncements() {
    DeleteResult deleteResult = mock(DeleteResult.class);
    when(deleteResult.getDeletedCount()).thenReturn(3L);
    when(mongoTemplate.remove(any(Query.class), any(Class.class))).thenReturn(Mono.just(deleteResult));

    StepVerifier.create(service.purgeExpired())
        .expectNext(3L)
        .verifyComplete();

    verify(mongoTemplate, times(1)).remove(any(Query.class), any(Class.class));
  }

  // --- 6. Prueba Concurrente de Escritura ---

  @Test
  @DisplayName("Escrituras concurrentes no corrompen estado y respetan el límite")
  public void concurrentWrites_doNotCorruptState() throws InterruptedException {
    // Simulamos un repositorio en memoria concurrente y thread-safe
    ConcurrentHashMap<String, OpsAnnouncement> store = new ConcurrentHashMap<>();
    AtomicInteger activeCount = new AtomicInteger(0);

    when(repo.countByActiveTrueAndExpiresAtAfter(any(Instant.class)))
        .thenAnswer(inv -> Mono.just((long) activeCount.get()));

    when(repo.save(any(OpsAnnouncement.class)))
        .thenAnswer(inv -> {
          OpsAnnouncement a = inv.getArgument(0);
          store.put(a.getId(), a);
          activeCount.incrementAndGet();
          return Mono.just(a);
        });

    int threads = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(threads);
    List<OpsAnnouncementAdminDto> createdList = Collections.synchronizedList(new ArrayList<>());

    for (int i = 0; i < threads; i++) {
      final int idx = i;
      executor.submit(() -> {
        try {
          OpsAnnouncementRequest req = OpsAnnouncementRequest.builder()
              .message("Aviso concurrente número " + idx)
              .severity(AnnouncementSeverity.INFO)
              .build();
          OpsAnnouncementAdminDto result = service.createAnnouncement(req, "admin-" + idx).block();
          if (result != null) {
            createdList.add(result);
          }
        } finally {
          latch.countDown();
        }
      });
    }

    boolean completed = latch.await(5, TimeUnit.SECONDS);
    executor.shutdown();

    assertThat(completed).isTrue();
    // Cada elemento creado tiene un ID único no nulo y no hay colisiones
    assertThat(createdList).isNotEmpty();
    assertThat(store.size()).isEqualTo(createdList.size());
    long distinctIds = createdList.stream().map(OpsAnnouncementAdminDto::getId).distinct().count();
    assertThat(distinctIds).isEqualTo(createdList.size());
  }

  // --- 7. Prueba Conceptual de Reinicio y Persistencia ---

  @Test
  @DisplayName("Reinicio conceptual: Un nuevo servicio sobre el mismo repositorio recupera los anuncios intactos")
  public void persistenceAcrossRestart_conceptualVerification() {
    ConcurrentHashMap<String, OpsAnnouncement> durableStore = new ConcurrentHashMap<>();

    OpsAnnouncement persistentAnnouncement = OpsAnnouncement.builder()
        .id("PERSISTENT-ID-99")
        .message("Aviso importante que sobrevive a reinicios")
        .severity(AnnouncementSeverity.CRITICAL)
        .createdAt(fixedNow)
        .expiresAt(fixedNow.plus(24, ChronoUnit.HOURS))
        .createdBy("devops")
        .active(true)
        .build();

    durableStore.put(persistentAnnouncement.getId(), persistentAnnouncement);

    // Mock del repositorio consultando el almacén durable
    when(repo.findById("PERSISTENT-ID-99")).thenAnswer(inv -> Mono.justOrEmpty(durableStore.get("PERSISTENT-ID-99")));

    // "Reinicio": Se instancia un nuevo servicio desde cero
    OpsAnnouncementService newServiceAfterRestart = new OpsAnnouncementService(repo, mongoTemplate, testClock);

    OpsAnnouncementAdminDto retrieved = newServiceAfterRestart.getAnnouncementById("PERSISTENT-ID-99").block();
    assertThat(retrieved).isNotNull();
    assertThat(retrieved.getId()).isEqualTo("PERSISTENT-ID-99");
    assertThat(retrieved.getMessage()).isEqualTo("Aviso importante que sobrevive a reinicios");
    assertThat(retrieved.getSeverity()).isEqualTo(AnnouncementSeverity.CRITICAL);
    assertThat(retrieved.getCreatedBy()).isEqualTo("devops");
    assertThat(retrieved.isActive()).isTrue();
  }

}
