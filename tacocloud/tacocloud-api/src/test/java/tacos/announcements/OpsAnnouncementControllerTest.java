package tacos.announcements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.announcements.dto.OpsAnnouncementAdminDto;
import tacos.announcements.dto.OpsAnnouncementDto;
import tacos.announcements.dto.OpsAnnouncementRequest;

public class OpsAnnouncementControllerTest {

  private OpsAnnouncementService service;
  private OpsAnnouncementController controller;
  private Authentication adminAuth;

  @BeforeEach
  public void setUp() {
    service = mock(OpsAnnouncementService.class);
    controller = new OpsAnnouncementController(service);
    adminAuth = new UsernamePasswordAuthenticationToken("admin", "password", Collections.emptyList());
  }

  @Test
  @DisplayName("GET /api/announcements retorna anuncios activos sanitizados")
  public void getActiveAnnouncements_returnsPublicDtos() {
    OpsAnnouncementDto dto = OpsAnnouncementDto.builder()
        .id("ANN-1")
        .message("Sistema en mantenimiento")
        .severity(AnnouncementSeverity.WARN)
        .createdAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(7200))
        .build();

    when(service.getActiveAnnouncements()).thenReturn(Flux.just(dto));

    StepVerifier.create(controller.getActiveAnnouncements())
        .assertNext(res -> {
          assertThat(res.getId()).isEqualTo("ANN-1");
          assertThat(res.getMessage()).isEqualTo("Sistema en mantenimiento");
          assertThat(res.getSeverity()).isEqualTo(AnnouncementSeverity.WARN);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("POST /api/admin/announcements crea anuncio y retorna 201 Created")
  public void createAnnouncement_admin_returns201() {
    OpsAnnouncementRequest request = OpsAnnouncementRequest.builder()
        .message("Nuevo despliegue en progreso")
        .severity(AnnouncementSeverity.INFO)
        .durationMinutes(60L)
        .build();

    OpsAnnouncementAdminDto createdDto = OpsAnnouncementAdminDto.builder()
        .id("CREATED-ID")
        .message("Nuevo despliegue en progreso")
        .severity(AnnouncementSeverity.INFO)
        .createdBy("admin")
        .active(true)
        .build();

    when(service.createAnnouncement(any(OpsAnnouncementRequest.class), eq("admin")))
        .thenReturn(Mono.just(createdDto));

    StepVerifier.create(controller.createAnnouncement(request, adminAuth))
        .assertNext(response -> {
          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
          assertThat(response.getBody()).isNotNull();
          assertThat(response.getBody().getId()).isEqualTo("CREATED-ID");
          assertThat(response.getBody().getCreatedBy()).isEqualTo("admin");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("DELETE /api/admin/announcements/{id} retorna 204 No Content")
  public void deleteAnnouncement_returns204() {
    when(service.deleteAnnouncement("ID-TO-DELETE")).thenReturn(Mono.empty());

    StepVerifier.create(controller.deleteAnnouncement("ID-TO-DELETE"))
        .assertNext(response -> {
          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("POST /api/admin/announcements/purge retorna 200 OK con purgedCount")
  public void purgeExpired_returns200() {
    when(service.purgeExpired()).thenReturn(Mono.just(5L));

    StepVerifier.create(controller.purgeExpiredAnnouncements())
        .assertNext(response -> {
          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(response.getBody()).isNotNull();
          assertThat(response.getBody().get("purgedCount")).isEqualTo(5L);
        })
        .verifyComplete();
  }

}
