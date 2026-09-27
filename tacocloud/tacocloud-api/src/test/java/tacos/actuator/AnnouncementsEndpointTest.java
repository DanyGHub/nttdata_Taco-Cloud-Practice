package tacos.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.announcements.AnnouncementSeverity;
import tacos.announcements.OpsAnnouncementService;
import tacos.announcements.dto.OpsAnnouncementAdminDto;
import tacos.announcements.dto.OpsAnnouncementDto;
import tacos.announcements.dto.OpsAnnouncementRequest;

public class AnnouncementsEndpointTest {

  private OpsAnnouncementService service;
  private AnnouncementsEndpoint endpoint;

  @BeforeEach
  public void setUp() {
    service = mock(OpsAnnouncementService.class);
    endpoint = new AnnouncementsEndpoint(service);
  }

  @Test
  @DisplayName("ReadOperation expone anuncios activos de forma segura")
  public void readOperation_returnsActiveAnnouncements() {
    OpsAnnouncementDto dto = OpsAnnouncementDto.builder()
        .id("ANN-123")
        .message("Operaciones normales")
        .severity(AnnouncementSeverity.INFO)
        .createdAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(3600))
        .build();

    when(service.getActiveAnnouncements()).thenReturn(Flux.just(dto));

    StepVerifier.create(endpoint.getAnnouncements())
        .assertNext(res -> {
          assertThat(res.getId()).isEqualTo("ANN-123");
          assertThat(res.getMessage()).isEqualTo("Operaciones normales");
          assertThat(res.getSeverity()).isEqualTo(AnnouncementSeverity.INFO);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("WriteOperation delega la creación con severidad y duración a OpsAnnouncementService")
  public void writeOperation_createsAnnouncement() {
    OpsAnnouncementAdminDto created = OpsAnnouncementAdminDto.builder()
        .id("NEW-UUID")
        .message("Nueva nota operacional")
        .severity(AnnouncementSeverity.WARN)
        .createdBy("ACTUATOR")
        .active(true)
        .build();

    when(service.createAnnouncement(any(OpsAnnouncementRequest.class), eq("ACTUATOR")))
        .thenReturn(Mono.just(created));

    StepVerifier.create(endpoint.addAnnouncement("Nueva nota operacional", "WARN", 120L))
        .assertNext(res -> {
          assertThat(res.getId()).isEqualTo("NEW-UUID");
          assertThat(res.getMessage()).isEqualTo("Nueva nota operacional");
          assertThat(res.getSeverity()).isEqualTo(AnnouncementSeverity.WARN);
          assertThat(res.getCreatedBy()).isEqualTo("ACTUATOR");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("DeleteOperation elimina anuncio por ID y no por índice posicional")
  public void deleteOperation_deletesById() {
    when(service.deleteAnnouncement("STABLE-ID-77")).thenReturn(Mono.empty());

    StepVerifier.create(endpoint.deleteAnnouncement("STABLE-ID-77"))
        .assertNext((Map<String, Object> map) -> {
          assertThat(map).containsEntry("deleted", "STABLE-ID-77");
        })
        .verifyComplete();
  }

}
